# Material do Aluno — Aula 7: Tópicos / eventos com Kafka

> **Tempo de leitura:** ~12 min. Esta aula muda o eixo do módulo: até aqui você mandava trabalho para ser feito (fila, comando, alguém consome **uma vez**). Agora você vai **publicar fatos** que muitos podem ler, no seu ritmo, inclusive no futuro. Essa distinção — trabalho × fato — é a diferença entre uma fila e um tópico, e ela reorganiza toda a arquitetura. Leia com atenção a seção da chave de partição: é o detalhe que mais derruba sistemas Kafka em produção.

---

## 1. O problema: vários querem reagir ao mesmo fato

No nosso projeto-guia, o `comprovante-gravador` acabou de persistir um comprovante PIX. Esse fato — "comprovante gravado" — interessa a várias áreas:

- **Notificação** quer avisar o cliente ("seu comprovante está disponível").
- **Antifraude** quer analisar o padrão da transação.
- **BI** quer somar a métrica para o dashboard de volumetria.

A solução ingênua é o gravador chamar os três: `notificacaoClient.enviar(...)`, `antifraudeClient.analisar(...)`, `biClient.registrar(...)`. Funciona até a primeira mudança. Amanhã entra uma quarta área (compliance), depois uma quinta (data lake). Cada nova reação obriga a **mexer no gravador** e a **subir o gravador de novo**. Pior: se a antifraude está fora do ar, a chamada síncrona dela pode travar a gravação — uma área secundária derrubando o coração do sistema.

O gravador virou um **ponto de acoplamento que cresce sem parar**. Ele conhece todos os interessados, depende da disponibilidade de todos e carrega a responsabilidade de orquestrar todos. Isso é exatamente o oposto do que o DDD da Aula 1 nos ensinou: o emissor de um fato **não deveria conhecer** quem reage a ele.

A inversão que resolve: em vez de o gravador **chamar** os interessados, ele **publica um fato** num lugar central — "comprovante gravado, aqui estão os dados" — e **quem se interessa que escute**. O gravador não sabe (e não quer saber) quantos consumidores existem. Adicionar um novo consumidor passa a ser uma operação que **não toca no produtor**. Esse "lugar central onde fatos são publicados e muitos leem" é um **tópico**.

---

## 2. Fila × tópico: a diferença que muda a arquitetura

Esta é a distinção mais importante da aula. Fila e tópico parecem a mesma coisa — "lugar onde mensagens passam" — mas resolvem problemas opostos.

| | **Fila** (ex.: RabbitMQ) | **Tópico** (ex.: Kafka) |
|---|---|---|
| A mensagem é... | **trabalho** a ser feito | **fato** que aconteceu |
| Consumida por... | **um** worker (entre vários concorrentes) | **vários** consumidores independentes |
| Depois de processada | **some** (ack remove da fila) | **permanece** no log (retenção) |
| Semântica | "faça isto" (comando) | "isto aconteceu" (evento) |
| Quem conhece quem | produtor sabe que há trabalho a distribuir | produtor **ignora** quem consome |
| Reprocessar o passado | impossível (já sumiu) | possível (**replay**) |

Na **fila**, a mensagem é uma tarefa. Vários workers competem por ela, mas **apenas um** a pega — distribuir carga é o objetivo. Quando o worker confirma (ack), a mensagem desaparece. Foi exatamente o que usamos nas Aulas 4 e 5: "grave este comprovante" é um **comando**, um trabalho, consumido uma vez pelo gravador.

No **tópico**, a mensagem é um fato registrado num **log**. Ela não é endereçada a ninguém; fica disponível. **Cada** consumidor interessado lê o log inteiro, no seu ritmo, mantendo seu próprio progresso. Notificação, antifraude e BI leem **os mesmos eventos**, de forma independente, sem disputar entre si e sem que um afete o outro.

> Não existe "Kafka é melhor que RabbitMQ". São ferramentas para problemas diferentes. **Regra prática:** se a frase natural é um verbo no imperativo ("grave o comprovante"), é **comando → fila**. Se é um fato no passado ("comprovante foi gravado"), é **evento → tópico**. Repare que essa é a mesma distinção comando/evento do event storming da Aula 1 — o desenho do domínio já apontava onde usar cada um.

---

## 3. O modelo mental do Kafka

Kafka não é uma "fila com superpoderes". É um **log distribuído, append-only**. Entender cinco peças destrava tudo o mais.

**Log append-only.** Um tópico é, na essência, um arquivo onde eventos só são **acrescentados ao fim**, nunca alterados nem removidos no meio. Cada evento ganha uma posição sequencial. Esse "só anexa" é o que torna o Kafka rápido (escrita sequencial em disco) e o que viabiliza o replay.

**Partição.** Um tópico é dividido em **partições**, que são logs paralelos. Partição é a **unidade de paralelismo** (cada partição pode ser lida por uma instância diferente) **e a unidade de ordenação** (a ordem só é garantida *dentro* de uma partição). Um tópico `comprovante-gravado` com 6 partições aceita até 6 consumidores de um grupo lendo em paralelo.

**Offset.** É a posição de leitura dentro de uma partição — um número que avança. O ponto crucial: **o offset pertence ao consumer group, não ao tópico**. O grupo `notificacao` está no offset 1.020 enquanto o grupo `bi` está no offset 340; cada um avança no seu ritmo, lendo os mesmos eventos. É por isso que múltiplos consumidores não interferem entre si.

**Consumer group.** Um conjunto de instâncias que **dividem** as partições de um tópico entre si. Dentro de um grupo, cada partição é lida por **uma só** instância — é assim que o Kafka escala o consumo. Entre grupos diferentes, **cada grupo lê tudo**. Ou seja: dentro do grupo = distribuição de carga (como fila); entre grupos = broadcast (como tópico). Os dois comportamentos no mesmo mecanismo.

**Replicação.** Cada partição é copiada em vários brokers (réplicas). Se o broker líder de uma partição cai, uma réplica assume. É o que dá a durabilidade que justifica usar o log como fonte da verdade.

```
Tópico "comprovante-gravado", 3 partições:

P0: [e0][e3][e6][e9]  ← append-only, offsets crescentes
P1: [e1][e4][e7]
P2: [e2][e5][e8][e10]

Grupo "notificacao" (2 instâncias):  inst-A lê P0,P1 | inst-B lê P2
Grupo "bi" (1 instância):            inst-C lê P0,P1,P2
→ cada grupo tem seu próprio offset em cada partição
```

---

## 4. A chave de partição: ordenação e o perigo da partição quente

A pergunta que decide a qualidade do seu design: **em qual partição um evento cai?** A resposta vem da **chave** da mensagem. O Kafka calcula `hash(chave) % número_de_partições`. Consequências diretas:

- **Mesma chave → mesma partição → ordem garantida** entre esses eventos.
- **Chaves diferentes → partições possivelmente diferentes → sem garantia de ordem** entre elas.
- **Chave nula → distribuição round-robin** (espalha, mas perde qualquer agrupamento por ordem).

No nosso domínio, a chave natural do tópico `comprovante-gravado` é o **`id` do comprovante**. Assim, todos os eventos de um mesmo comprovante (gravado, depois corrigido, depois reemitido) caem na mesma partição e são lidos **na ordem em que aconteceram**. Isso importa: notificar "comprovante corrigido" antes de "comprovante gravado" seria um bug.

Mas a escolha da chave tem duas armadilhas clássicas:

**Quebrar a ordem.** Se você precisa de ordem por comprovante mas usa o `id` do *cliente* como chave, dois comprovantes do mesmo cliente caem juntos — talvez não seja o que você quer. Pior: se não usa chave nenhuma onde a ordem importa, dois eventos do mesmo comprovante podem ir para partições diferentes e ser processados fora de ordem. **Ordem no Kafka é por partição, e partição é decidida pela chave.** Não há ordem global "de graça".

**Partição quente (hot partition).** Se a chave tem **baixa cardinalidade** ou é **enviesada**, uma partição recebe a maioria das mensagens. Exemplo perigoso: usar o `tipoChave` do PIX (CPF, e-mail, telefone, aleatória — só 4 valores) como chave do tópico. Com 6 partições, no máximo 4 são usadas, e se 80% dos PIX são por CPF, **uma partição recebe 80% da carga**. Aquele consumidor satura enquanto os outros ficam ociosos — você comprou paralelismo e não pode usá-lo. Boa chave de partição tem **alta cardinalidade e distribuição equilibrada**; o `id` (UUID) do comprovante é ideal.

> Resumo que vale ouro numa banca: **a chave controla simultaneamente a ordenação e o balanceamento.** Escolha pensando em "o que precisa ser lido em ordem" e "o que distribui bem". Quando esses dois objetivos brigam, é sinal de que o modelo de eventos precisa de revisão.

---

## 5. Retenção e replay

Numa fila, a mensagem some quando é processada. No Kafka, **o evento permanece no log** por um período configurado (`retention.ms`) — dias, semanas, ou até "para sempre" em tópicos compactados. Consumir **não apaga**. Essa propriedade aparentemente simples destrava capacidades que a fila não tem.

**Replay.** Você pode **reposicionar o offset** de um consumer group para trás e reprocessar eventos já lidos. Cenários reais:

- A notificação tinha um bug que formatava a mensagem errada por 3 dias. Você corrige o código e **reprocessa os últimos 3 dias** — os eventos ainda estão lá.
- O índice do BI corrompeu. Você reconstrói reprocessando o log desde o início, sem precisar de backup separado: **o log é o backup**.

**Plugar um consumidor novo no passado.** Quando uma nova área (compliance) precisa reagir a comprovantes gravados, ela sobe um consumer group novo e começa a ler **do offset zero**. Ela "vê" toda a história que aconteceu **antes de existir**, sem que ninguém precise reenviar nada e sem afetar os consumidores existentes (cada grupo tem seu offset). Numa fila isso seria impossível: o passado já foi consumido e descartado.

---

## 6. Event sourcing: o log como fonte da verdade

Quando você leva a ideia do log retido até o fim, chega ao **event sourcing**: em vez de guardar apenas o **estado atual** ("comprovante está GRAVADO"), você guarda a **sequência de fatos** que levou a ele ("emitido", "aceito", "gravado", "consultado"). O estado atual passa a ser uma **projeção** — algo que você **calcula** reproduzindo os eventos, não algo que você armazena como verdade primária.

Por que isso é poderoso:

- **Auditoria nativa.** O log *é* a trilha de auditoria. Para um sistema financeiro como o da Caixa, poder responder "por que este comprovante está neste estado?" reproduzindo cada fato é um requisito, não um luxo.
- **Reconstrução de estado.** Se a base de leitura corrompe ou você precisa de uma nova visão dos dados, reproduz os eventos e reconstrói. O passado é reproduzível.
- **Múltiplas projeções do mesmo log.** Notificação, antifraude e BI são, cada um, uma **projeção diferente** do mesmo fluxo de eventos. Uma nova pergunta de negócio vira uma nova projeção — sem migração de schema.

Event sourcing tem custo (complexidade, versionamento de eventos, *snapshots* para não reproduzir milhões de eventos toda vez) e nem todo sistema precisa dele. Mas o **modelo mental** — "o fato é a verdade, o estado é derivado" — é o que torna o Kafka mais que um cano de mensagens.

> **Rebalance (em uma frase):** quando uma instância de um consumer group entra ou sai, o Kafka **redistribui as partições** entre as instâncias restantes (rebalance). Durante esse rearranjo o consumo pausa brevemente; por isso processamento idempotente importa — uma partição pode ser reprocessada a partir do último offset confirmado. Voltaremos à idempotência na próxima aula.

---

## 7. Exemplo trabalhado: `comprovante-gravado` com três consumer groups

Vamos do produtor aos três consumidores independentes, no nosso domínio.

### Passo 1 — O evento (um fato, imutável)

Um evento de domínio descreve algo que **já aconteceu**. Nome no passado, dados completos para quem reage não precisar voltar perguntar:

```java
public record ComprovanteGravadoEvent(
        UUID comprovanteId,
        String chavePixDestino,
        BigDecimal valor,
        Instant gravadoEm) {
}
```

### Passo 2 — O produtor (o gravador publica e esquece)

Logo após persistir, o gravador publica o fato. A **chave** é o `id` do comprovante (ordenação por comprovante + boa distribuição):

```java
@Service
public class GravadorService {

    private final KafkaTemplate<String, ComprovanteGravadoEvent> kafkaTemplate;

    public GravadorService(KafkaTemplate<String, ComprovanteGravadoEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void gravar(Comprovante comprovante) {
        repository.save(comprovante); // fonte da verdade persistida

        var evento = new ComprovanteGravadoEvent(
                comprovante.getId(),
                comprovante.getDestino().valor(),
                comprovante.getValor().valor(),
                Instant.now());

        // chave = id do comprovante → mesma partição, ordem preservada
        kafkaTemplate.send("comprovante-gravado", comprovante.getId().toString(), evento);
    }
}
```

O gravador não conhece notificação, antifraude nem BI. Publica o fato e segue. Esse é o desacoplamento que queríamos.

### Passo 3 — Três consumidores, três `groupId`

Cada consumidor declara um **`groupId` diferente**. É isso que faz os três lerem **os mesmos eventos de forma independente**, cada um com seu offset:

```java
@Component
public class NotificacaoListener {

    @KafkaListener(topics = "comprovante-gravado", groupId = "notificacao")
    public void aoGravar(ComprovanteGravadoEvent e) {
        notificacaoService.avisarCliente(e.comprovanteId(), e.chavePixDestino());
    }
}

@Component
public class AntifraudeListener {

    @KafkaListener(topics = "comprovante-gravado", groupId = "antifraude")
    public void analisar(ComprovanteGravadoEvent e) {
        antifraudeService.avaliarPadrao(e.comprovanteId(), e.valor());
    }
}

@Component
public class BiListener {

    @KafkaListener(topics = "comprovante-gravado", groupId = "bi")
    public void agregar(ComprovanteGravadoEvent e) {
        metricaService.incrementarVolumetria(e.valor(), e.gravadoEm());
    }
}
```

### Passo 4 — Adicionar um quarto consumidor (sem tocar no produtor)

Amanhã, compliance precisa reagir. A mudança é **um arquivo novo**, com um `groupId` novo. O gravador permanece intocado. E se compliance precisa ver os comprovantes gravados **antes de existir**, basta configurar o grupo para ler do início (`auto-offset-reset: earliest`) — o log retido entrega o passado:

```java
@Component
public class ComplianceListener {

    // groupId novo → começa do offset zero e "vê" toda a história já gravada
    @KafkaListener(topics = "comprovante-gravado", groupId = "compliance")
    public void registrar(ComprovanteGravadoEvent e) {
        complianceService.arquivar(e);
    }
}
```

Esse é o ganho concreto da arquitetura orientada a eventos: **o sistema cresce por adição, não por modificação**. Cada nova reação é um consumidor novo, isolado, que não conhece e não afeta os outros.

---

## 8. Ponte com o legado Caixa

Quem operou mainframe já fez isto sem o nome. O **"arquivo de movimento do dia"** — aquele arquivo sequencial que vários jobs noturnos liam, cada um fazendo sua parte (um atualizava saldo, outro gerava relatório, outro alimentava o data warehouse) — **já era um log de eventos**. Vários consumidores, o mesmo arquivo, ninguém apagava a parte do outro, e o produtor (o sistema online do dia) não sabia quem eram os jobs noturnos.

O que o Kafka muda **não é o conceito** — é o regime: aquele log era **batch** (uma vez por dia, arquivo fechado) e o Kafka é **streaming contínuo** (o fato fica disponível em milissegundos, não no fechamento do dia). O offset por consumer group é o equivalente moderno de cada job saber "até onde já processei o arquivo de hoje". Quem entende o ciclo de movimento do legado entende Kafka mais rápido que a média — é a mesma ideia, em tempo real e com durabilidade distribuída.

---

## 9. IA & agentes hoje

A arquitetura orientada a eventos virou a espinha dorsal de sistemas de IA sérios:

- **Event-driven para agentes.** Em vez de um orquestrador chamar cada agente diretamente (recriando o acoplamento do gravador ingênuo), os agentes **reagem a eventos** num log. Um agente publica "documento processado"; agentes de resumo, classificação e indexação reagem, cada um no seu grupo. Adicionar um agente novo não toca nos existentes — exatamente o consumer group novo da seção 7.
- **Event sourcing como memória do agente.** O log de eventos (cada ação, cada observação, cada decisão) é a **memória reproduzível** do agente. Dá auditoria ("por que o agente decidiu isto?") e **replay** (reexecutar a trajetória com um prompt corrigido), do mesmo jeito que reproduzimos o estado de um comprovante. Para um agente em produção financeira, essa trilha não é opcional.
- **Streaming para ingestão e RAG.** Novos documentos entram como eventos num tópico; um consumidor calcula embeddings e **atualiza o índice vetorial continuamente**, sem reprocessar a base inteira. O índice vira uma projeção do log de documentos — a mesma ideia da seção 6, aplicada ao conhecimento do agente.

---

## 10. Para ir além

- **Documentação Apache Kafka** — conceitos de tópico, partição, offset e consumer group (o ponto de partida canônico).
- **Martin Kleppmann**, *Designing Data-Intensive Applications* — caps. sobre logs, streams e a dualidade tabela/log; a melhor explicação de event sourcing.
- **Spring for Apache Kafka** — `KafkaTemplate`, `@KafkaListener` e configuração de consumer groups.
- **Confluent** — padrões de event-driven architecture e *event sourcing* aplicado.

> **Na próxima aula:** o tópico desacoplou os consumidores, mas criou um problema novo. O consumidor de notificação depende de um **gateway externo que oscila**. Quando ele cai e volta, o que acontece? Tentamos de novo — mas tentar de novo **sem piorar o incidente** é engenharia. Entram retry com backoff, idempotência e circuit breaker.
