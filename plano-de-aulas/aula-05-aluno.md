# Material do Aluno — Aula 5: Comunicação assíncrona (producer/consumer)

> **Tempo de leitura:** ~12 min. Esta é a aula em que o sistema deixa de ser "uma request, uma resposta, tudo no mesmo fio" e passa a ter **partes que vivem em ritmos diferentes**. O conceito central — desacoplamento — parece simples, mas cada decisão que ele abre (mensagem ou evento? quantas vezes a mensagem chega? e se o consumidor cair?) tem trade-offs que separam quem leu sobre filas de quem opera filas em produção. O exemplo trabalhado da seção 7 é o coração do projeto-guia.
>
> **Onde isto encaixa:** a Aula 3 deu o *mapa* dos três eixos da comunicação (síncrono×assíncrono, comando×evento, orquestração×coreografia) e o lugar de broker/ESB/Camel. Esta aula pega o eixo **síncrono→assíncrono** e o constrói de verdade.

---

## 1. O problema gerador: aceitar agora, processar depois

No projeto de Comprovantes PIX, a gravação do comprovante na base de verdade é a parte **lenta e instável** do fluxo. Ela depende de um banco que, em dia de pico (pagamento, 13º), oscila: às vezes responde em 30 ms, às vezes em 4 s, às vezes está fora por alguns segundos durante um failover. O cliente, do outro lado, acabou de fazer um PIX e quer o comprovante **agora**.

A solução ingênua é síncrona: o `POST /comprovantes` valida, grava no banco e só então responde `201 Created`. Funciona no laboratório. Em produção ela tem dois defeitos fatais:

- **Acoplamento de disponibilidade:** se o banco está fora, a emissão está fora. Uma falha na parte mais frágil derruba a parte mais visível. O cliente recebe `500` por algo que nem é culpa da emissão.
- **Acoplamento de latência:** o cliente paga o tempo da gravação. No pico, isso vira timeout, retry do app mobile, request duplicada e a sensação de "o comprovante demora a aparecer".

A saída é inverter a lógica: **aceitar agora e processar depois**. A emissão valida o que precisa validar de forma síncrona (a regra de negócio, os value objects), responde **`202 Accepted`** com o `id` do comprovante e **publica** uma tarefa de gravação numa fila. Outro componente — o consumidor — grava no seu próprio tempo, com suas próprias tentativas, sem o cliente esperando.

Você não eliminou o trabalho lento. Você o **moveu para fora do caminho da resposta**. E ao fazer isso, assumiu um conjunto de responsabilidades novas que esta aula inteira existe para detalhar.

> O `202 Accepted` é uma promessa, não uma confirmação. Ele diz "recebi e vou processar", não "gravei". Isso muda o contrato com o cliente: a consulta passa a poder retornar "ainda processando" por uma janela curta — exatamente o *hotspot* que o event storming da Aula 1 já tinha previsto.

---

## 2. Producer e consumer: o desacoplamento temporal

O padrão tem duas pontas e um intermediário:

- **Producer (produtor):** publica mensagens **sem saber quem** vai consumir, nem **quando**. Ele conhece a *intenção* ("preciso que este comprovante seja gravado"), não o destinatário.
- **Broker (intermediário):** guarda a mensagem de forma durável até alguém consumir. É o RabbitMQ, o Kafka, o IBM MQ.
- **Consumer (consumidor):** processa no **seu ritmo**, podendo escalar em paralelo (vários consumidores na mesma fila) ou pausar e retomar.

O ganho que justifica toda a complexidade é o **desacoplamento temporal**: produtor e consumidor **não precisam estar vivos ao mesmo tempo** — o broker é o buffer que absorve o pico e segura a mensagem enquanto o consumidor não dá conta.

> O trade-off completo **síncrono × assíncrono** (disponibilidade, latência, absorção de pico, acoplamento de localização, custo operacional) você já mapeou na **Aula 3, §2** — não vamos repetir a tabela. O essencial para esta aula: o assíncrono **não é grátis**; troca simplicidade por disponibilidade e elasticidade, e a §8 fecha com **quando não fazer isso**.

---

## 3. Mensagem, comando e evento: três coisas diferentes

A **Aula 3 (§3)** já deu a regra que separa os dois — *"consigo adicionar um consumidor sem tocar no produtor? → evento"*. Aqui ela vira **decisão de projeto**, com a armadilha que mais derruba arquitetura distribuída. Relembrando em uma linha:

- **Comando** = *"faça isto"*: dirigido a **um** dono lógico, no imperativo (`GravarComprovanteCommand`); o produtor **espera** a ação e sabe que a pediu.
- **Evento** = *"isto aconteceu"*: um fato no passado (`ComprovanteGravadoEvent`) que **qualquer interessado** observa; o produtor **não sabe nem se importa** com quem reage (zero, um ou dez: notificação, antifraude, BI).

| Aspecto | Comando | Evento |
|---|---|---|
| Significado | "faça isto" | "isto aconteceu" |
| Tempo verbal | imperativo (`Gravar...`) | particípio (`...Gravado`) |
| Destinatário | um (quem deve agir) | N interessados (broadcast) |
| Acoplamento | produtor conhece a intenção da ação | produtor ignora quem consome |
| Acoplamento de evolução | quebrar muda quem executa | adicionar consumidor não afeta o produtor |
| Exemplo no PIX | `GravarComprovanteCommand` (Aulas 5/6) | `ComprovanteGravadoEvent` (Aula 7) |

**Por que a distinção é de projeto, não de detalhe?** Porque ela define **a direção do acoplamento**. Quando o produtor de um "evento" começa a esperar uma ação específica de um consumidor específico — "publiquei `ComprovanteGravado` e *preciso* que o serviço de notificação faça X" — esse evento virou um **comando disfarçado**, e você recriou o acoplamento que a fila deveria ter quebrado. É a regra da Aula 3 vista pelo avesso: o acoplamento que você jurou eliminar voltando pela porta dos fundos.

No projeto PIX, a gravação é um **comando** (a emissão *pede* que se grave; há um responsável claro). Já o "comprovante foi gravado" é um **evento** (a gravação anuncia um fato; quem reage é problema de quem reage). Esta aula trata do comando; a Aula 7 trata do evento.

---

## 4. Garantias de entrega — e o mito do exactly-once

Quando uma mensagem cruza a rede, há três níveis possíveis de garantia. Cada um tem um custo, e o custo é o que ninguém conta nos slides.

- **At-most-once (no máximo uma vez):** a mensagem é entregue zero ou uma vez. Nunca duplica, mas **pode perder**. O produtor dispara e esquece; se o broker cair antes de persistir, a mensagem some. Custo baixo, perda aceitável só para dados descartáveis (métrica de telemetria, log de UI). **Inaceitável** para um comprovante bancário.
- **At-least-once (ao menos uma vez):** a mensagem **nunca se perde**, mas **pode duplicar**. O produtor reenvia se não recebeu confirmação; o broker reentrega se o consumidor não confirmou (ack). É o **padrão prático** da indústria, porque "perder um comprovante" é catastrófico e "gravar duas vezes" é um problema que você **pode** resolver.
- **Exactly-once (exatamente uma vez):** o ideal — nunca perde, nunca duplica. É o que todo mundo quer e quase ninguém entrega de verdade.

### O mito do exactly-once

Entrega exatamente-uma-vez **de ponta a ponta** é, na prática distribuída, impossível de garantir só no transporte. O raciocínio: o consumidor processa a mensagem e, antes de confirmar o ack, cai. O broker, sem o ack, **tem** que reentregar — mas não consegue distinguir "processou e caiu" de "não processou e caiu". Portanto, em algum cenário de falha, ou ele perde ou ele duplica. Não há terceira opção no transporte puro.

O que se chama de "exactly-once" em produtos comerciais é, no fundo, **at-least-once + idempotência no consumidor**: a mensagem pode chegar duas vezes, mas o **efeito final** é "como se" tivesse chegado uma. Você não evita a duplicata; você a torna **inofensiva**.

> A frase para guardar: *exactly-once delivery* é mito; *exactly-once processing* (efeito) é alcançável — e a ferramenta é a idempotência do consumidor, não uma mágica do broker. Quem promete exactly-once puro geralmente está escondendo a premissa de que o seu processamento já é idempotente.

---

## 5. Idempotência do consumidor (na prática)

Idempotência é a propriedade de uma operação que, executada **uma ou N vezes com a mesma entrada, produz o mesmo resultado**. Como o broker entrega *at-least-once*, o consumidor **tem** que ser idempotente — não é opcional, é o que torna o at-least-once seguro.

A estratégia mais direta para a gravação do comprovante é usar o **identificador de negócio** (o `id` do comprovante, gerado na emissão) como chave de deduplicação. Antes de gravar, verifica se já existe:

```java
@Component
public class GravadorDeComprovante {

    private final ComprovanteRepository repositorio;

    public GravadorDeComprovante(ComprovanteRepository repositorio) {
        this.repositorio = repositorio;
    }

    // Idempotente: reprocessar a MESMA mensagem não cria um segundo comprovante.
    public void gravar(GravarComprovanteCommand cmd) {
        if (repositorio.existsById(cmd.identificadorComprovante())) {
            return; // já gravado numa entrega anterior — nada a fazer
        }
        repositorio.save(mapear(cmd));
    }
}
```

Esse `existsById` resolve o caso comum, mas tem uma janela de corrida: duas entregas concorrentes da mesma mensagem podem **ambas** passar pelo `if` antes de qualquer `save`. Em produção, a defesa robusta não é o `if` — é uma **constraint de unicidade no banco** (`id` como chave primária, ou um índice único na chave de negócio). O banco rejeita a segunda inserção, e o consumidor trata a `DataIntegrityViolationException` como "já gravado".

A regra mental: **a idempotência confiável mora no armazenamento durável** (constraint), não na verificação em memória. O `existsById` é otimização; a constraint é a garantia.

---

## 6. Backpressure: quando o consumidor não dá conta

A fila absorve picos — mas ela não é infinita, e o consumidor não é mágico. Quando a taxa de chegada (publicações) supera a taxa de saída (processamento) por tempo suficiente, a fila **cresce sem parar**. Isso é o sinal de **backpressure**: o sistema downstream está dizendo "estou mais lento do que você me alimenta".

Cenários no PIX:

- **Consumidor lento:** o banco está degradado; cada gravação leva 2 s em vez de 30 ms. A fila incha durante o pico e drena depois — aceitável se a memória/disco do broker aguentam o pico.
- **Consumidor caído:** o gravador está fora. A fila cresce **indefinidamente**. Aqui o desacoplamento temporal salva o cliente (a emissão continua respondendo `202`), mas a dívida se acumula no broker.

O que fazer diante do backpressure — em ordem de preferência:

1. **Escalar consumidores horizontalmente:** subir mais instâncias do gravador na mesma fila. É a resposta certa quando o gargalo é o consumidor e o recurso a jusante (o banco) aguenta a concorrência.
2. **Limitar o que cada consumidor puxa (prefetch/QoS):** evita que um consumidor "engula" milhares de mensagens e estoure a própria memória (detalhe da Aula 6).
3. **Throttle na entrada / load shedding:** se nem escalando o recurso a jusante aguenta, é melhor **rejeitar na borda** (`503`/`429` na emissão) do que deixar a fila crescer até o broker cair. Falhar rápido e visível supera falhar devagar e invisível.
4. **Alarmar pela profundidade da fila:** o tamanho da fila é o termômetro mais direto da saúde do sistema. Fila crescendo de forma monotônica = incidente em formação.

> O erro clássico é tratar a fila como "banco infinito" e **não monitorar a profundidade**. A fila cresce silenciosamente, o disco do broker enche, e o que era um problema de latência vira uma queda total — inclusive da emissão, que dependia do broker para publicar.

---

## 7. Exemplo trabalhado: o `POST` do PIX ponta a ponta

Vamos montar o fluxo completo: a emissão responde `202` e publica o comando; o consumidor grava de forma idempotente. Stack: Spring Boot 3 / Spring AMQP / Java 21.

O comando que trafega pela fila — um `record` imutável, com a chave de idempotência embutida:

```java
public record GravarComprovanteCommand(
        UUID identificadorComprovante,   // chave de negócio = chave de idempotência
        ComprovanteRequest dados
) {}
```

O controller valida, delega e responde `202` **sem esperar a gravação**:

```java
@RestController
@RequestMapping("/comprovantes")
public class EmissaoController {

    private final EmissorDeComprovante emissor;

    public EmissaoController(EmissorDeComprovante emissor) {
        this.emissor = emissor;
    }

    @PostMapping
    public ResponseEntity<ComprovanteAceito> emitir(@RequestBody ComprovanteRequest req) {
        UUID id = emissor.aceitarEPublicar(req); // valida + publica o comando
        return ResponseEntity
                .accepted() // 202: "recebi, vou processar"
                .body(new ComprovanteAceito(id, LocalDateTime.now()));
    }
}
```

O emissor **gera o id**, valida de forma síncrona e publica numa exchange (a anatomia da exchange é a Aula 6; aqui foco no padrão produtor):

```java
@Service
public class EmissorDeComprovante {

    private final RabbitTemplate rabbit;

    public EmissorDeComprovante(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public UUID aceitarEPublicar(ComprovanteRequest req) {
        validarRegrasDeNegocio(req);  // síncrono e rápido: VOs, formato, limites
        UUID id = UUID.randomUUID();   // identidade gerada AQUI, na aceitação

        rabbit.convertAndSend(
                "comprovantes.exchange",  // exchange (não a fila diretamente)
                "comprovante.gravar",     // routing key = a intenção
                new GravarComprovanteCommand(id, req));

        return id; // devolvido no 202, para o cliente consultar depois
    }
}
```

O consumidor, idempotente, do outro lado da fila:

```java
@Component
public class GravacaoListener {

    private final ComprovanteRepository repositorio;

    public GravacaoListener(ComprovanteRepository repositorio) {
        this.repositorio = repositorio;
    }

    @RabbitListener(queues = "gravacao.q")
    public void aoReceber(GravarComprovanteCommand cmd) {
        if (repositorio.existsById(cmd.identificadorComprovante())) {
            return; // mensagem reentregue (at-least-once): ignora com segurança
        }
        repositorio.save(mapear(cmd));
    }

    private Comprovante mapear(GravarComprovanteCommand cmd) {
        return Comprovante.aPartirDe(cmd);
    }
}
```

O que esse fluxo entrega: a emissão responde em milissegundos mesmo com o banco degradado; o pico vira fila, não timeout; e a duplicata inevitável do at-least-once é absorvida pela idempotência. O que **ainda falta** — o que acontece quando a gravação falha 3 vezes seguidas, ou quando a mensagem está malformada e nunca vai gravar — é o gancho da Aula 6.

---

## 8. Quando **não** usar assíncrono

Assíncrono é uma ferramenta, não uma medalha. Ele adiciona um broker para operar, duplicação para tratar, ordenação para pensar e uma classe inteira de bugs ("a mensagem sumiu?") para depurar. Pagar esse preço sem necessidade é dívida técnica disfarçada de arquitetura moderna.

Mantenha **síncrono** quando:

- **A resposta precisa do resultado para continuar.** Se o cliente não pode prosseguir sem o dado processado (ex.: autorizar uma transação e devolver "aprovado/negado" na hora), o assíncrono só adiciona um round-trip de consulta.
- **O trabalho é rápido e estável.** Se a operação leva 10 ms e quase nunca falha, a fila não compra nada — só adiciona latência e infraestrutura.
- **A consistência forte imediata é requisito.** Se a regra exige que o efeito seja visível atomicamente (a invariante de um agregado, Aula 1), processar depois quebra a garantia.
- **O volume não justifica.** Sem pico, sem instabilidade a jusante e sem fan-out, você está construindo um caminhão para carregar um envelope.

> A pergunta de triagem: *"o cliente precisa do resultado para a próxima ação dele?"* Se **sim**, comece síncrono. Se **não** — e há latência, instabilidade ou pico no caminho — o assíncrono se paga.

---

## 9. Ponte com o legado Caixa

Quem operou **IBM MQ (MQSeries) com JMS** já fez producer/consumer décadas atrás — e fez bem. As filas de entrada e saída do mainframe, os processos batch que liam de uma fila e escreviam em outra, o desacoplamento entre o CICS que aceitava e o job noturno que processava: tudo isso **é** comunicação assíncrona. O conceito de "aceitar agora, processar depois" nasceu no mundo dos grandes processadores transacionais, não na nuvem.

O que mudou não é o conceito — é o **ferramental e o ecossistema**. O broker hoje é RabbitMQ ou Kafka em vez de MQSeries; a observabilidade é métrica de profundidade de fila em painel em vez de relatório de fila; a escala é horizontal e elástica em vez de capacidade fixa. Mas a garantia de entrega, a deduplicação por chave (o veterano de batch já chamava de "controle de reprocessamento") e a fila de rejeitados (a futura DLQ) são velhos conhecidos. Nesta aula, quem vem do legado costuma ser quem mais rápido entende o **porquê** — aproveite para liderar a explicação.

---

## 10. IA & agentes hoje

A mesma disciplina de desacoplamento governa os sistemas de IA modernos — e por uma razão idêntica: a parte cara e instável não pode ficar no caminho síncrono.

- **Desacoplar inferência cara:** uma chamada de LLM leva segundos, custa dinheiro por token e falha de formas variadas (timeout, rate limit, conteúdo recusado). Colocá-la no caminho síncrono de uma request HTTP é repetir o erro da gravação do PIX. O padrão certo é o mesmo: aceitar a tarefa, responder `202`, publicar numa fila e deixar **workers** processarem. O cliente consulta o resultado depois (polling ou webhook).
- **Filas de tarefas de agentes:** um orquestrador publica tarefas (`PesquisarDocumentoTask`, `GerarResumoTask`); agentes-worker consomem da fila, escalam horizontalmente conforme a carga e **isolam falhas** — um worker que trava não derruba o orquestrador. É producer/consumer com o LLM no lugar do banco.
- **Human-in-the-loop assíncrono:** um passo que exige aprovação humana ("este agente quer executar uma transação de R$ 50 mil — aprovar?") é, por natureza, assíncrono: o agente publica o pedido, **suspende** aquele fluxo e retoma quando a resposta humana chega — minutos ou horas depois. Tentar fazer isso síncrono é segurar uma thread esperando um humano, o pior dos mundos.

A intuição transfere direto: o que você aprendeu sobre `202`, idempotência e backpressure no comprovante PIX é exatamente o que se aplica a um pipeline de agentes em produção.

---

## 11. Para ir além

- **Gregor Hohpe & Bobby Woolf**, *Enterprise Integration Patterns* — a bíblia dos padrões de mensageria (Message, Command Message, Event Message, Idempotent Receiver).
- **Tyler Treat**, *"You Cannot Have Exactly-Once Delivery"* — o artigo que desmonta o mito com o raciocínio completo de falha.
- **Spring AMQP Reference** — conceitos de `RabbitTemplate`, `@RabbitListener` e conversão de mensagens.
- **Sam Newman**, *Building Microservices* (cap. de comunicação) — quando preferir orquestração síncrona vs. coreografia assíncrona.

> **Na próxima aula (Aula 6 — RabbitMQ + DLQ):** o consumidor desta aula tem um buraco. Quando a gravação falha por **azar momentâneo** (banco oscilou), reprocessar resolve. Mas quando a mensagem está **malformada** e vai falhar **sempre**, reprocessar para sempre trava a fila inteira — é a *poison message*. Como distinguir os dois tipos de falha, reprocessar um com **retry e backoff** e isolar o outro numa **Dead Letter Queue** sem nunca perder o dado? É a entrada do RabbitMQ de verdade: exchanges, bindings, ack manual, prefetch e DLQ.
