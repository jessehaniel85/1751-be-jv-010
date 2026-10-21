# Material do Aluno — Aula 6: Filas com RabbitMQ + idempotência e DLQ

> **Tempo de leitura:** ~13 min. Na Aula 4 você desacoplou a emissão da gravação com uma fila "pronta". Agora abrimos a caixa-preta: como uma fila de verdade **roteia**, **confirma** e **isola** mensagens — e por que o tratamento de falha não é um detalhe de configuração, é o coração do design. O fio condutor é uma pergunta concreta: *quando a gravação do comprovante falha três vezes seguidas, a mensagem some?* A resposta — "nunca por acidente" — depende de cada peça desta aula. O exemplo trabalhado da seção 8 mostra como **provar** a DLQ com uma mensagem envenenada.

---

## 1. Dois tipos de falha que não podem ser tratados igual

A gravação do comprovante PIX falha de **dois jeitos fundamentalmente diferentes**, e a confusão entre eles é a origem de quase todo incidente de fila em produção:

- **Falha transitória:** algo momentâneo. O banco oscilou durante um failover, a conexão caiu, houve um deadlock, o pool de conexões esgotou por um instante. A mensagem está **perfeita**; só teve azar de chegar num mau momento. **Tentar de novo resolve.**
- **Falha permanente:** algo estrutural. A mensagem está malformada (um campo obrigatório nulo, um valor que viola uma constraint, um enum desconhecido por incompatibilidade de versão). Tentar de novo **nunca** vai resolver — vai falhar exatamente igual, para sempre.

Tratar os dois do mesmo jeito leva a um de dois desastres:

| Estratégia única | Consequência na falha transitória | Consequência na falha permanente |
|---|---|---|
| **Descartar ao primeiro erro** | perde comprovante bom por azar momentâneo — catastrófico | "limpa" a fila, mas perde o dado e a evidência do bug |
| **Reprocessar para sempre** | correto: a próxima tentativa funciona | trava a fila num loop infinito de falha (a *poison message*) |

A arquitetura certa **distingue** os dois: falha transitória → **retry com backoff** (tenta de novo, espaçado, algumas vezes); falha permanente → **Dead Letter Queue** (isola para inspeção humana, sem nunca descartar). O resto da aula é construir essa distinção peça por peça.

---

## 2. Anatomia do RabbitMQ: por que não se publica direto na fila

A primeira surpresa de quem vem de uma `BlockingQueue` em memória: no RabbitMQ (e no AMQP em geral) o produtor **não publica numa fila**. Ele publica numa **exchange**, e a exchange decide para quais filas a mensagem vai. Há quatro peças:

- **Exchange:** o ponto de entrada. Recebe a mensagem do produtor e a **roteia**. Não armazena nada.
- **Queue (fila):** onde a mensagem **fica** até ser consumida. É a parte durável.
- **Binding:** a "ligação" que conecta uma exchange a uma fila, com uma regra.
- **Routing key:** o rótulo que o produtor põe na mensagem; a exchange compara a routing key com os bindings para decidir o destino.

```
            routing key = "comprovante.gravar"
producer ─────────────────────────────▶ [ exchange ] ──binding──▶ [ fila gravacao.q ] ──▶ consumer
                                              │
                                              └──binding──▶ [ fila auditoria.q ] ──▶ consumer
```

**Por que essa indireção?** Porque ela é exatamente o desacoplamento da Aula 3 sobre mensagem × evento, agora em infraestrutura. O produtor conhece a **intenção** (a exchange + a routing key), **não o consumidor**. Adicionar um segundo consumidor (uma fila de auditoria que também quer toda gravação) é criar um novo binding — **sem tocar no produtor**. Se o produtor publicasse direto na fila, ele estaria acoplado a cada consumidor existente. A exchange é o que torna o sistema extensível.

### Tipos de exchange

A regra de roteamento depende do **tipo** da exchange:

| Tipo | Regra de roteamento | Quando usar | Exemplo no PIX |
|---|---|---|---|
| **direct** | routing key **exata** = chave do binding | comando para um destino específico | `comprovante.gravar` → fila de gravação |
| **topic** | padrão com curingas (`*` = uma palavra, `#` = várias) | roteamento por categoria/hierarquia | `comprovante.gravado` para BI; `comprovante.*` para auditoria |
| **fanout** | **ignora** a routing key; replica para **todas** as filas ligadas | broadcast puro de um evento | "comprovante gravado" para notificação + antifraude + BI (Aula 6) |
| **headers** | casa por atributos do cabeçalho em vez de routing key | roteamento por metadados complexos | raro; roteamento por região/tipo |

Para a **fila de gravação** (um comando, um destino), o tipo certo é **direct**: a routing key `comprovante.gravar` cai exatamente na fila do gravador. Quando, na Aula 6, o evento "comprovante gravado" precisar chegar a vários interessados de uma vez sem o produtor saber quem são, o tipo certo será **fanout** ou **topic**. Guarde o contraste: **direct para comando, fanout/topic para evento** — é a mesma distinção da Aula 4, materializada em exchange.

---

## 3. Ack, nack manual e redelivery

Por padrão, um consumidor poderia confirmar a mensagem **no momento em que a recebe** (auto-ack). Isso é perigoso: se o consumidor cai **depois** de receber e **antes** de gravar, a mensagem já foi confirmada e **se perde**. Para um comprovante, inaceitável.

A configuração correta é **ack manual**: o consumidor confirma (**ack**) **só depois** de processar com sucesso. As três respostas possíveis ao broker:

- **ack (acknowledge):** "processei com sucesso, pode descartar a mensagem". O broker remove da fila.
- **nack (negative ack) / reject:** "não consegui processar". Aqui há uma escolha crítica: `requeue=true` devolve a mensagem à fila (para nova tentativa); `requeue=false` **descarta ou envia para dead-letter** (veremos na seção 6).
- **nenhuma resposta + queda do consumidor:** o broker não recebeu ack, então **reentrega** (redelivery) a mensagem — para o mesmo ou outro consumidor.

```java
@RabbitListener(queues = "gravacao.q", ackMode = "MANUAL")
public void aoReceber(GravarComprovanteCommand cmd, Channel canal,
                      @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
    try {
        gravar(cmd);                 // processa
        canal.basicAck(tag, false);  // confirma só após sucesso
    } catch (FalhaTransitoria e) {
        canal.basicNack(tag, false, true);  // requeue=true → tenta de novo
    } catch (MensagemInvalida e) {
        canal.basicNack(tag, false, false); // requeue=false → vai para a DLQ
    }
}
```

O `redelivery` é precisamente por que a **idempotência** da Aula 4 é obrigatória: a mesma mensagem **vai** chegar duas vezes em algum cenário de crash, e o consumidor tem que tratar a segunda chegada sem duplicar o comprovante. Ack manual e idempotência são as duas metades da entrega confiável.

> Na prática com Spring AMQP, em vez de manipular o `Channel` na mão, você costuma deixar o **container do listener** gerenciar o ack (`AUTO` mode, que faz ack após retorno sem exceção e nack na exceção) e configurar o comportamento de retry/dead-letter por **configuração** — código mais limpo e menos sujeito a erro. O exemplo manual acima existe para você ver o que acontece por baixo.

---

## 4. Prefetch / QoS: quantas mensagens não-confirmadas por consumidor

Se um consumidor puxa **todas** as mensagens da fila de uma vez para a memória local antes de processá-las, dois problemas surgem: ele pode estourar a própria memória, e o trabalho fica **desbalanceado** — um consumidor "engole" 10 mil mensagens enquanto outro fica ocioso.

O **prefetch count** (configurado via `basic.qos`) limita **quantas mensagens não-confirmadas (in-flight)** o broker entrega a cada consumidor antes de receber o ack delas. É o controle de QoS (Quality of Service) do consumo.

```java
@Bean
public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
        ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
    var factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(jsonMessageConverter);
    factory.setPrefetchCount(10);   // no máx. 10 mensagens in-flight por consumidor
    factory.setDefaultRequeueRejected(false); // exceção → dead-letter, não requeue infinito
    factory.setAdviceChain(retryInterceptor());
    return factory;
}
```

A heurística de tuning:

- **Prefetch baixo (1–10):** melhor **balanceamento** entre consumidores e menor risco de uma instância acumular trabalho que não vai dar conta. Ideal para tarefas pesadas e demoradas, como a gravação que depende do banco.
- **Prefetch alto (centenas):** melhor **throughput** quando cada mensagem é leve e rápida, porque reduz o ida-e-volta de pedir mais mensagens. Pior balanceamento.

Para a gravação do PIX (tarefa de IO, latência variável), um prefetch baixo é o ponto de partida sensato: você prefere que mensagens sobrem na fila (visíveis, escaláveis) a que sobrem acumuladas dentro de um consumidor (invisíveis, perdidas se ele cai). Prefetch também é uma das alavancas de **backpressure** da Aula 4: limitar o in-flight evita que o consumidor afogue o banco a jusante.

---

## 5. Retry com backoff: a resposta à falha transitória

Para a falha **transitória**, a estratégia é tentar de novo — mas **não imediatamente nem para sempre**. Tentar de novo na mesma fração de segundo, contra um banco que acabou de cair, é um *retry storm*: você martela um recurso já sofrendo e prolonga o incidente (assunto da Aula 7).

A combinação certa é **retry com backoff exponencial e limite**:

- **Backoff exponencial:** espaça as tentativas crescentemente — 1 s, 2 s, 4 s, 8 s — dando ao recurso a jusante tempo para se recuperar.
- **Limite de tentativas:** depois de N tentativas (ex.: 3 a 5), você **para**. Se ainda falha, ou o problema não era transitório, ou está durando demais para reprocessar in-line — em ambos os casos, a mensagem vai para a DLQ.

Com Spring AMQP isso se configura declarativamente, sem poluir o listener:

```java
@Bean
public RetryOperationsInterceptor retryInterceptor() {
    return RetryInterceptorBuilder.stateless()
            .retryPolicy(new SimpleRetryPolicy(4,             // 1 original + 3 retries
                    Map.of(AmqpRejectAndDontRequeueException.class, false), // permanente: sem retry
                    true, true))
            .backOffOptions(1000, 2.0, 10000)               // 1s, 2s, 4s... teto 10s
            .recoverer(new RejectAndDontRequeueRecoverer()) // esgotou → dead-letter
            .build();
}
```

O `RejectAndDontRequeueRecoverer` é a peça que liga o retry à DLQ: quando as tentativas se esgotam, ele faz `nack` com `requeue=false`, e **aí** o mecanismo de dead-lettering entra em ação. Sem ele, o default seria requeue infinito — o loop da poison message.

O `SimpleRetryPolicy` classifica a `AmqpRejectAndDontRequeueException` (a falha permanente, seção 8) como **não-retentável**: sem esse mapa, o interceptor retentaria também a mensagem malformada e ela só chegaria à DLQ depois de esgotar o backoff.

---

## 6. Dead Letter Queue: o destino da falha permanente

A **Dead Letter Queue (DLQ)** é uma fila lateral para onde uma mensagem é desviada quando **não pode ser entregue/processada normalmente**. O ponto inegociável: a mensagem é **preservada para inspeção**, **nunca descartada em silêncio**. Uma mensagem que "some" sem rastro é um comprovante perdido e um bug invisível; a DLQ existe para que isso jamais aconteça por acidente.

Uma mensagem é "dead-lettered" (enviada à DLQ) em três situações:

1. O consumidor faz `nack`/`reject` com `requeue=false` (nosso caso de mensagem inválida ou retry esgotado).
2. A mensagem **expira** (TTL estourado) sem ser consumida.
3. A fila atinge o **limite de tamanho** (`max-length`) e descarta a mais antiga para a DLQ.

O roteamento para a DLQ não é mágico: é o **mesmo** mecanismo de exchange/binding da seção 2. A fila principal declara, nos seus argumentos, uma **dead-letter-exchange** para onde mandar os mortos:

```java
@Bean
public Queue gravacaoQueue() {
    return QueueBuilder.durable("gravacao.q")
            .withArgument("x-dead-letter-exchange", "gravacao.dlx")    // para onde mandar
            .withArgument("x-dead-letter-routing-key", "comprovante.morto") // com que rótulo
            .build();
}

@Bean
public Queue dlq() {
    return QueueBuilder.durable("gravacao.dlq").build(); // a fila dos mortos
}

@Bean
public DirectExchange deadLetterExchange() {
    return new DirectExchange("gravacao.dlx");
}

@Bean
public Binding dlqBinding() {
    return BindingBuilder.bind(dlq())
            .to(deadLetterExchange())
            .with("comprovante.morto"); // rota da DLX até a DLQ
}
```

Os argumentos `x-dead-letter-*` são o contrato: `x-dead-letter-exchange` diz **para qual exchange** a mensagem morta vai, e `x-dead-letter-routing-key` (opcional) **reescreve a routing key** para que a DLX a entregue na DLQ certa. A mensagem chega na DLQ com **headers de diagnóstico** (`x-death`) que registram **quantas vezes** ela morreu, **de qual fila** e **por quê** — material de ouro para o time investigar o bug que a causou.

> A DLQ não é uma lixeira; é uma **sala de necropsia**. Toda mensagem ali é um incidente a ser entendido: ou um bug de dados a corrigir, ou uma incompatibilidade de versão, ou um cenário que o código não previu. O fluxo operacional maduro inclui **alarmar quando a DLQ cresce** e, muitas vezes, um processo de **reprocessamento** após corrigir a causa.

---

## 7. Poison message e ordenação

A **poison message (mensagem envenenada)** é a mensagem que **falha permanentemente** mas o sistema insiste em reprocessar. Sem retry limitado + DLQ, ela vira um loop infinito: o consumidor pega, falha, devolve à fila (requeue), pega de novo, falha de novo — consumindo CPU, gerando log infinito e, pior, **bloqueando** as mensagens boas atrás dela. Uma única mensagem malformada pode parar toda a gravação de comprovantes.

A defesa é exatamente a arquitetura que montamos: **limite de tentativas** (a poison não fica eternamente) + **DLQ** (ela sai do caminho das mensagens boas, mas não se perde). É por isso que "retry sem limite" e "consumidor sem DLQ" são, juntos, a receita do desastre.

Sobre **ordenação**: o RabbitMQ garante ordem **FIFO dentro de uma única fila com um único consumidor**. Assim que você adiciona **múltiplos consumidores** (para escalar) ou **retry/redelivery** (uma mensagem que falha e volta entra atrás das que chegaram depois), a ordem **deixa de ser garantida**. Para a gravação de comprovantes isso é aceitável — cada comprovante é independente, identificado pelo seu `id`, e a idempotência cuida das duplicatas. Mas é um trade-off a declarar: **se o seu domínio exige ordem estrita, escala horizontal e fila simples não convivem** — você precisaria de particionamento por chave (terreno do Kafka, próxima aula).

---

## 8. Exemplo trabalhado: provar a DLQ com uma mensagem envenenada

Montar a topologia é metade do trabalho; **provar** que ela funciona é a outra. O objetivo: injetar uma mensagem que falha sempre e **demonstrar** que ela acaba na DLQ — sem travar a fila e sem se perder.

O consumidor, que distingue os dois tipos de falha:

```java
@Component
public class GravacaoListener {

    private final GravadorService gravador;

    public GravacaoListener(GravadorService gravador) {
        this.gravador = gravador;
    }

    @RabbitListener(queues = "gravacao.q")
    public void aoReceber(GravarComprovanteCommand cmd) {
        if (cmd.identificadorComprovante() == null || cmd.dados() == null
                || cmd.dados().valorTransacao() == null) {
            // falha PERMANENTE: nenhum retry vai consertar isto
            throw new AmqpRejectAndDontRequeueException("comprovante malformado");
        }
        gravador.gravar(cmd); // idempotente sob redelivery; pode lançar falha TRANSITÓRIA → retry
    }
}
```

A distinção está nos dois caminhos de exceção:

- `AmqpRejectAndDontRequeueException` sinaliza ao Spring AMQP "**não** tente de novo" → a mensagem vai **direto** para a DLQ. É a resposta à falha permanente, sem desperdiçar tentativas.
- Qualquer outra exceção (ex.: `DataAccessException` do banco oscilando) passa pelo **retry com backoff** da seção 5; só vai para a DLQ se as 4 tentativas se esgotarem.

**O roteiro do experimento** (o que você fará no studio):

1. Publique um `GravarComprovanteCommand` **válido** → confirme que grava normalmente e dá ack.
2. Publique um comando com `valorTransacao` nulo → observe que ele **não** fica em loop; vai **imediatamente** para `gravacao.dlq`.
3. Abra a fila `gravacao.dlq` no **painel de management** do RabbitMQ → veja a mensagem **preservada**, com os headers `x-death` mostrando a origem e o motivo.
4. Publique outro comando **válido** logo depois do envenenado → confirme que ele **passa na frente**: a poison message **não bloqueou** a fila.

Os passos 2 e 4 juntos são a prova que importa: a falha permanente foi **isolada** (não travou) e **preservada** (não sumiu). Essa é a definição operacional de "mensagem nunca some por acidente — ou foi processada, ou está numa DLQ por decisão".

---

## 9. Ponte com o legado Caixa

Quem operou processamento **batch** no mainframe já conhece a DLQ por outro nome: a **"fila de exceção"**, o **"arquivo de rejeitados"**, o dataset de registros que o job não conseguiu processar e separou para tratamento posterior. O conceito é idêntico — não descartar o que falhou, separá-lo para análise — e a maturidade operacional de "todo dia alguém olha os rejeitados" é exatamente a cultura que a DLQ moderna exige.

O que mudou é que a DLQ hoje é **explícita, observável e instrumentada**: em vez de um arquivo perdido num diretório que alguém *talvez* abra, é uma fila com **métrica de profundidade**, **alarme** quando cresce, e **headers de diagnóstico** (`x-death`) que contam a história da falha. O retry com backoff, da mesma forma, é a versão observável do velho "tenta de novo no próximo ciclo do batch" — agora com política configurável e por mensagem, não por job inteiro. O veterano de batch entende essa aula mais rápido que ninguém: ele já viveu o que acontece quando um único registro envenenado derruba o processamento da madrugada inteira.

---

## 10. IA & agentes hoje

A topologia de filas + DLQ é, hoje, infraestrutura padrão de pipelines de IA — pelas mesmas razões que no comprovante PIX, com o LLM no lugar do banco instável.

- **Work queue para jobs de IA com rate limit:** vários workers consomem chamadas de LLM em paralelo, e a **fila vira o regulador de vazão**. O provedor de modelo impõe um limite de requisições por minuto; controlar o **prefetch** e o número de workers é como você respeita esse limite sem estourar `429` — a fila absorve o excesso em vez de rejeitá-lo.
- **Backpressure quando o LLM é o gargalo:** quando a inferência é mais lenta que a chegada de tarefas, a fila cresce. Esse é o **sinal de saúde** mais direto: profundidade subindo = escalar workers ou throttlar a entrada, exatamente como na gravação do PIX. Monitorar a fila do agente é monitorar o agente.
- **DLQ para chamadas de IA que falham:** um timeout, um conteúdo recusado pelo provedor, uma resposta que não casa com o schema esperado — são as *poison messages* do mundo de IA. Em vez de reprocessar para sempre (queimando dinheiro a cada tentativa contra um LLM) ou descartar em silêncio (perdendo a tarefa do usuário), a chamada falha vai para uma **DLQ** e entra em **inspeção humana**. A distinção transitório × permanente é a mesma: um `503` momentâneo do provedor merece retry com backoff; um prompt que sempre viola a política de conteúdo é falha permanente que precisa de gente olhando.

Tudo o que você configurou aqui — exchange, binding, ack manual, prefetch, retry com backoff, DLQ — é transferível, sem adaptação conceitual, para orquestrar agentes em produção.

---

## 11. Para ir além

- **RabbitMQ Tutorials** — especialmente *Work Queues*, *Routing*, *Topics* e *Dead Letter Exchanges* (a documentação oficial, com exemplos executáveis).
- **Spring AMQP Reference** — *Message Listener Container*, *Retry*, *Dead Letter* e configuração de `x-dead-letter-*` por `QueueBuilder`.
- **Gregor Hohpe & Bobby Woolf**, *Enterprise Integration Patterns* — *Dead Letter Channel* e *Invalid Message Channel* (os padrões que a DLQ implementa).
- **RabbitMQ in Depth** (Gavin M. Roy) — para entender o protocolo AMQP por baixo das abstrações do Spring.

> **Na próxima aula:** a fila de gravação resolve **um comando para um destino**. Mas o event storming da Aula 1 deixou uma política em aberto: *"sempre que um comprovante é gravado, notificar o cliente, avisar o antifraude e alimentar o BI"*. São **três** interessados no **mesmo** fato, e o gravador não deve nem saber que eles existem. Filas ponto-a-ponto não modelam isso bem — é a entrada de **tópicos e publish/subscribe**, e de um broker pensado para fan-out e replay em escala: o **Kafka**.
