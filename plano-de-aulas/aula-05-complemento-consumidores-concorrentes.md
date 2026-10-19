# Complemento: várias instâncias consumindo a mesma fila

> **Tempo de leitura:** ~8 min. Leitura pós-aula. Na Aula 5 surgiu esta pergunta: *"Tenho um serviço escalado horizontalmente, com N instâncias, e todas consomem a mesma fila. Como o broker sabe para qual instância mandar cada mensagem?"* A resposta passa por quatro pontos: quem inicia a entrega, o papel do *prefetch*, as estratégias de distribuição e o lugar do load balancer, inclusive com o broker em cluster. Os exemplos usam o RabbitMQ.

---

## 1. O broker empurra ou o consumidor busca?

**O broker não precisa saber onde as instâncias estão. São elas que se conectam a ele.**

- Ao subir, cada instância abre uma **conexão TCP de longa duração** com o broker e se inscreve na fila (`basic.consume`). O recado é: "estou aqui, me mande mensagens desta fila".
- Daí em diante o modelo é **push**. O broker envia as mensagens pelo canal já aberto, e a instância não precisa perguntar se há algo novo.
- Com várias instâncias inscritas na mesma fila, temos o padrão **competing consumers**. Cada mensagem vai para **um único** consumidor, distribuída por padrão em **round-robin**.
- Também existe o modo pull (`basic.get`), em que a instância pede uma mensagem por vez. Em produção ele é raro porque vira *polling*: gasta rede e aumenta a latência.

```
            ┌──────── conexão aberta pela instância ────────┐
 Fila  ──►  Broker ──push msg1──► instância A
                   ──push msg2──► instância B
                   ──push msg3──► instância C
                   ──push msg4──► instância A ...
```

**E se a instância B cair no meio do processamento da msg2?** Como ela ainda não confirmou a mensagem com *ack*, o broker a devolve à fila quando a conexão cai e a entrega a outra instância. Por isso a entrega é *at-least-once* (pelo menos uma vez), e o consumidor precisa ser **idempotente**: processar a mesma mensagem duas vezes não pode gerar efeito duplicado.

---

## 2. Prefetch: quanto cada consumidor pode segurar

O *prefetch* (`basic.qos`) define **quantas mensagens sem *ack* o broker pode deixar com cada consumidor ao mesmo tempo**.

| Configuração | Comportamento | Risco |
|---|---|---|
| Sem limite (0) | O broker despeja a fila nos consumidores assim que eles se conectam | A primeira instância a subir recebe quase tudo; uma instância lenta acumula mensagens enquanto outra fica ociosa |
| Prefetch = 1 | Só manda a próxima depois do *ack* da anterior, o que dá uma distribuição justa (*fair dispatch*) | Vazão menor, porque há uma ida e volta de rede por mensagem |
| Prefetch = N (ex.: 10 a 250) | Equilíbrio: cada instância mantém um pequeno estoque local | Com N alto, uma instância lenta segura mensagens que outra poderia processar |

- No Spring AMQP o padrão é **250** (`spring.rabbitmq.listener.simple.prefetch`).
- **Regra prática:** processamento lento ou de custo variável pede prefetch baixo; processamento rápido e homogêneo aceita prefetch alto.
- **Efeito colateral:** com N consumidores, **a ordem global da fila se perde**, porque msg1 e msg2 são processadas em paralelo, em instâncias diferentes.

---

## 3. O round-robin é a única estratégia?

No RabbitMQ não há um parâmetro do tipo "estratégia de distribuição" para escolher. O round-robin é o comportamento padrão, e quatro recursos o modificam:

| Recurso | O que faz |
|---|---|
| **Prefetch** | Torna a distribuição justa: só recebe quem tem capacidade livre |
| **Consumer priority** (`x-priority` no `basic.consume`) | Consumidores de prioridade alta recebem primeiro; os de prioridade baixa só recebem quando os outros estão cheios |
| **Single active consumer** (`x-single-active-consumer` na fila) | Só um consumidor recebe e os demais ficam de reserva. Garante a ordem, ao custo do paralelismo |
| **Streams** (RabbitMQ 3.9+) | Fila em formato de log, lida por posição (*offset*). Com *super streams* há particionamento por chave |

Os *exchanges* (`direct`, `topic`, `fanout`, `headers`) decidem **para qual fila** a mensagem vai, e não **para qual consumidor**. Mesmo assim dá para combinar as peças: um *consistent-hash exchange* (plugin) com uma fila por instância faz com que **a mesma chave sempre caia na mesma instância**, preservando a ordem por entidade (por exemplo, por conta).

### E em outros brokers?

| Broker | Opções de distribuição |
|---|---|
| **Apache Pulsar** | A estratégia se escolhe na assinatura: `Exclusive`, `Failover`, `Shared` (round-robin) e `Key_Shared` (por hash da chave) |
| **ActiveMQ / Artemis** | *Message groups* (`JMSXGroupID`): a mesma chave sempre vai para o mesmo consumidor. Há também fila exclusiva e prioridade de consumidor |
| **Azure Service Bus** | *Sessions*: as mensagens com o mesmo `SessionId` vão para um único consumidor, em ordem |
| **Amazon SQS** | Pull com *long polling*: quem pede primeiro leva a mensagem. Na fila FIFO, o `MessageGroupId` garante ordem por grupo |

**O padrão que se repete:** os brokers acabam oferecendo as mesmas três escolhas.

1. **Distribuir livremente**, quando o que importa é vazão.
2. **Fixar por chave**, quando o que importa é a ordem por entidade.
3. **Um ativo e os demais de reserva**, quando o que importa é a ordem total.

---

## 4. E o load balancer?

**O load balancer não participa do consumo.** Quem distribui as mensagens entre as instâncias é o próprio broker.

- O load balancer (ou o `Service` do Kubernetes) existe para o tráfego **de entrada**, como as requisições HTTP que chegam à API.
- O consumo da fila é tráfego **de saída**: a instância liga para o broker.
- Para escalar o consumo, basta subir mais réplicas, e cada uma se inscreve sozinha na fila. Dá para automatizar pela profundidade da fila, por exemplo com KEDA no Kubernetes.

```
Clientes HTTP ──► Load Balancer ──► instâncias A, B, C   (entrada: o LB distribui)
instâncias A, B, C ──► Broker                            (consumo: o broker distribui)
```

---

## 5. Com o RabbitMQ em cluster

Para a aplicação, o cluster se comporta **logicamente como um só**. Fisicamente, não é bem assim:

- Os nós compartilham os **metadados**: exchanges, filas, bindings e usuários.
- O **conteúdo de cada fila** fica num **nó líder**. Na *quorum queue*, o tipo recomendado hoje, há um líder eleito (protocolo Raft) e réplicas nos outros nós.
- O cliente pode se conectar a **qualquer nó**. Se esse nó não for o líder da fila, as mensagens passam internamente pelo líder: funciona, mas acrescenta um salto de rede.

Há dois jeitos de as instâncias encontrarem o cluster:

1. **Load balancer TCP** (HAProxy, NLB, `Service` do Kubernetes) na frente dos nós. A aplicação enxerga um único endereço.
2. **Lista de endereços no cliente**, por exemplo `spring.rabbitmq.addresses=rmq1:5672,rmq2:5672,rmq3:5672`. O próprio cliente escolhe um nó e troca de nó se ele cair.

Cuidados com o load balancer na frente do broker:

- Ele **balanceia conexões, não mensagens**. Como as conexões duram muito, depois que um nó reinicia a distribuição pode ficar desequilibrada até as conexões se refazerem.
- O *idle timeout* do load balancer precisa ser **maior que o heartbeat do AMQP** (60 s por padrão). Caso contrário, ele derruba conexões que estão apenas ociosas.
- Se o líder de uma *quorum queue* cai, uma réplica assume, e os consumidores reconectam sozinhos (no Spring, pelo *auto-recovery*). A aplicação nem percebe a troca de nó.
- Nos **Streams**, o cliente costuma precisar se conectar direto ao nó que guarda os dados, e um load balancer no meio atrapalha.

---

## Para levar

- O broker não procura as instâncias: **elas se conectam e se inscrevem**.
- O padrão é **push** com **round-robin**, e o **prefetch** decide quão justa é a distribuição.
- Com várias instâncias, **a ordem global se perde** e o consumidor precisa ser **idempotente**.
- Para ordem por entidade, **fixe por chave**; para ordem total, use **um consumidor ativo**.
- Load balancer serve para o tráfego de entrada e, no máximo, para as **conexões com o cluster do broker**. Ele **nunca** distribui mensagens.
