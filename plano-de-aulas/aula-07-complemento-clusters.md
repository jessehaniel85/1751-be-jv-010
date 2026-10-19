# Complemento — Clusters na prática: RabbitMQ e Kafka com Docker Compose

> **Tempo estimado:** ~20 min de demonstração, metade para cada broker. Na aula passada falamos do RabbitMQ em cluster e, na Aula 7, §3, da **replicação** do Kafka, mas só no papel. Aqui você sobe os dois clusters na sua máquina, derruba nós de propósito e vê o que acontece com as mensagens. Os arquivos estão em [`ambiente/clusters/`](../ambiente/clusters/). Tudo é opcional: o professor demonstra com a tela compartilhada, e quem quiser repete em casa.

---

## 0. Antes de começar

- **Docker Desktop** (ou Docker Engine) com o **Docker Compose v2** (`docker compose version` responde). Funciona em Windows, macOS e Linux; os comandos abaixo são de linha única e rodam igual no PowerShell e no terminal do Mac/Linux.
- **Um cluster por vez.** São três contêineres em cada um; suba o RabbitMQ, brinque, derrube, e só então suba o Kafka.
- **Portas usadas:** RabbitMQ 5672–5674 e 15672–15674; Kafka 19092, 29092 e 39092. Se o `docker-compose.yml` do projeto-guia estiver de pé, derrube-o antes (ele também usa a 5672 e a 15672).
- A primeira subida baixa as imagens do Docker Hub (`rabbitmq:4.1-management`, `apache/kafka:3.9.1` e `pivotalrabbitmq/perf-test:2.23.0`). Como o projeto-guia, na máquina corporativa, roda sem Docker, esta demonstração é para a sua máquina pessoal.

---

## 1. Por que três nós? A regra da maioria

Um broker sozinho é um **ponto único de falha**: caiu, a fila (ou o tópico) cai junto, mesmo com tudo durável e persistente. Durável garante que a mensagem **volta** quando o nó volta; não garante que ela fica **disponível** enquanto o nó está fora.

Cluster resolve isso com **cópias em outros nós**. E as cópias só servem se o cluster souber decidir, sem ambiguidade, qual delas vale. Os dois brokers resolvem com a mesma ideia: **maioria**.

| Nós no cluster | Maioria | Quedas toleradas |
|---|---|---|
| 1 | 1 | 0 |
| 3 | 2 | **1** |
| 5 | 3 | 2 |

Por isso se usa número **ímpar**: quatro nós toleram uma queda só, igual a três, e custam mais. É exatamente o que vamos provocar: derrubar **um** nó (o cluster segue) e depois **dois** (o cluster para de aceitar escrita, de propósito, para não perder dado).

---

## 2. RabbitMQ: três nós e quorum queue

```bash
cd ambiente/clusters/rabbitmq
```

### 2.1 Subir e conferir o cluster

```bash
docker compose up -d --wait
docker compose exec rabbit-1 rabbitmqctl cluster_status
```

**O que observar:** em *Running Nodes* aparecem `rabbit@rabbit-1`, `rabbit@rabbit-2` e `rabbit@rabbit-3`. Os nós se acharam sozinhos pela lista do [`rabbitmq.conf`](../ambiente/clusters/rabbitmq/rabbitmq.conf) e compartilham o mesmo *Erlang cookie* (a "senha" entre nós), definido no compose.

Abra o painel em http://localhost:15672 (guest / guest). Na aba *Overview*, a seção *Nodes* lista os três.

### 2.2 Duas filas, dois comportamentos

```bash
docker compose exec rabbit-1 rabbitmqadmin declare queue --name comprovante.gravar.quorum --type quorum --durable true
docker compose exec rabbit-3 rabbitmqadmin declare queue --name comprovante.gravar.classica --type classic --durable true
docker compose exec rabbit-1 rabbitmqctl list_queues name type leader members messages state
```

**O que observar:**

- `comprovante.gravar.quorum` tem um **líder** e três **membros**: o conteúdo é replicado nos três nós (protocolo Raft).
- `comprovante.gravar.classica` tem **um só membro**, o nó onde foi criada (`rabbit-3`). Os *metadados* dela (nome, bindings) existem no cluster todo, mas as **mensagens** moram só no `rabbit-3`.

> **Por que não "espelhar" a fila clássica?** O espelhamento de filas clássicas (*mirrored queues*) foi removido no RabbitMQ 4.0. Hoje, fila que precisa sobreviver à queda de um nó é **quorum queue**.

### 2.3 Encher as filas

O gerador de carga *PerfTest* publica 1.000 mensagens persistentes na quorum queue e 1 na clássica, sempre com *publisher confirms* (complemento da Aula 6, §5):

```bash
docker compose run --rm carga --uri amqp://guest:guest@rabbit-2 --queue comprovante.gravar.quorum --predeclared --producers 1 --consumers 0 --pmessages 1000 --flag persistent --confirm 50
docker compose run --rm carga --uri amqp://guest:guest@rabbit-2 --queue comprovante.gravar.classica --predeclared --producers 1 --consumers 0 --pmessages 1 --flag persistent --confirm 1
docker compose exec rabbit-1 rabbitmqctl list_queues name type leader messages state
```

Repare que o PerfTest se conectou ao `rabbit-2` e a fila tem líder no `rabbit-1`: a aplicação pode se conectar a **qualquer nó**, e o cluster encaminha internamente.

### 2.4 Derrubar o líder da quorum queue

Use o nó que aparece na coluna `leader` (na nossa execução, `rabbit-1`):

```bash
docker compose stop rabbit-1
docker compose exec rabbit-2 rabbitmqctl list_queues name type leader messages state
```

**O que observar:** a quorum queue tem **outro líder** (`rabbit-2` ou `rabbit-3`), continua `running` e com as **mesmas mensagens**. Os dois nós restantes são maioria (2 de 3), então elegeram um novo líder em segundos. Publique mais uma para provar que ela segue aceitando escrita:

```bash
docker compose run --rm carga --uri amqp://guest:guest@rabbit-2 --queue comprovante.gravar.quorum --predeclared --producers 1 --consumers 0 --pmessages 1 --flag persistent --confirm 1
```

No painel (agora em http://localhost:15673, porque o `rabbit-1` caiu), a aba *Queues and Streams* mostra o novo líder e o membro fora do ar.

### 2.5 Derrubar mais um: sem maioria

```bash
docker compose stop rabbit-3
docker compose exec rabbit-2 rabbitmqctl list_queues name type leader messages state
docker compose run --rm carga --uri amqp://guest:guest@rabbit-2 --queue comprovante.gravar.quorum --predeclared --producers 1 --consumers 0 --pmessages 10 --flag persistent --confirm 5 --confirm-timeout 5 --time 20
```

**O que observar:**

- A fila clássica está `down`: o único nó que tinha as mensagens dela (`rabbit-3`) caiu.
- A quorum queue sobrou com **1 de 3** membros. Sem maioria, ela **não confirma** escrita nenhuma: o PerfTest termina com `Waiting for publisher confirms for too long`. O broker prefere parar a aceitar uma mensagem que só existiria numa cópia.

### 2.6 Religar e conferir

O `up -d --wait` sobe de novo os contêineres parados e espera até ficarem saudáveis.

```bash
docker compose up -d --wait
docker compose exec rabbit-1 rabbitmqctl list_queues name type leader members messages state
```

**O que observar:** as duas filas voltam a `running`, com todas as mensagens confirmadas antes da pane. A fila clássica tem a mensagem dela de volta (era durável e persistente), mas ficou **indisponível** o tempo todo em que o `rabbit-3` esteve fora.

> **Detalhe que vale a aula inteira:** na nossa execução, a quorum queue tinha 1.001 mensagens confirmadas antes da segunda queda e voltou com 1.002. A mais foi uma das mensagens que o PerfTest enviou no passo 2.5, **sem confirm**, e que entrou quando a maioria voltou. Para o produtor, mensagem sem confirm não é nem sucesso nem perda: é **indefinida**. A resposta certa é reenviar, e por isso o consumidor precisa ser **idempotente** (Aula 6).

### 2.7 E na aplicação Spring?

Passe a lista de nós; se o nó da conexão cair, o cliente reconecta em outro:

```yaml
spring:
  rabbitmq:
    addresses: localhost:5672,localhost:5673,localhost:5674
```

E declare a fila como quorum:

```java
@Bean
Queue filaGravacao() {
    return QueueBuilder.durable("comprovante.gravar").quorum().build();
}
```

Em produção, no lugar da lista costuma haver um **load balancer TCP** ou o `Service` do Kubernetes na frente dos nós. Ele distribui **conexões**, nunca mensagens.

Para terminar: `docker compose down -v`.

---

## 3. Kafka: três brokers, réplicas e ISR

```bash
cd ambiente/clusters/kafka
```

### 3.1 Subir e conferir o quórum

```bash
docker compose up -d --wait
docker compose exec kafka-1 /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server kafka-1:9092 describe --status
```

**O que observar:** `CurrentVoters` lista os três nós. No modo **KRaft** (sem ZooKeeper) cada nó é **broker** (guarda partições) e **controller** (vota no quórum que guarda os metadados do cluster: tópicos, líderes, réplicas). `LeaderId` é o controller ativo.

### 3.2 Criar o tópico com três réplicas

```bash
docker compose exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --create --topic comprovante-gravado --partitions 3 --replication-factor 3
docker compose exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --describe --topic comprovante-gravado
```

Saída da nossa execução:

```
Topic: comprovante-gravado  PartitionCount: 3  ReplicationFactor: 3  Configs: min.insync.replicas=2
    Partition: 0  Leader: 2  Replicas: 2,3,1  Isr: 2,3,1
    Partition: 1  Leader: 3  Replicas: 3,1,2  Isr: 3,1,2
    Partition: 2  Leader: 1  Replicas: 1,2,3  Isr: 1,2,3
```

Como ler cada linha:

- **Leader** — o broker que atende leitura e escrita daquela partição. Repare que cada partição tem um líder **diferente**: a carga se espalha pelo cluster.
- **Replicas** — onde existem cópias. O primeiro da lista é o **líder preferido**.
- **Isr** (*in-sync replicas*) — as réplicas em dia com o líder. É daqui que sai um novo líder se o atual cair.
- **min.insync.replicas=2** — veio do compose (`KAFKA_MIN_INSYNC_REPLICAS`). Com `acks=all`, uma escrita só é aceita se **pelo menos 2** réplicas em sincronia a gravarem.

### 3.3 Produzir com chave e consumir com grupo

Em um terminal, abra o produtor e digite uma mensagem por linha no formato `chave:valor` (Ctrl+C para sair):

```bash
docker compose exec kafka-1 /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka-1:9092 --topic comprovante-gravado --property parse.key=true --property key.separator=: --producer-property acks=all
```

```
c-001:gravado
c-002:gravado
c-003:gravado
c-001:corrigido
c-004:gravado
c-002:reemitido
```

Leia com o grupo `notificacao`, conectando por **outro** broker:

```bash
docker compose exec kafka-2 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka-2:9092 --topic comprovante-gravado --group notificacao --from-beginning --property print.key=true --property print.partition=true --property print.offset=true --max-messages 6
```

```
Partition:0  Offset:0  c-002  gravado
Partition:0  Offset:1  c-002  reemitido
Partition:2  Offset:0  c-003  gravado
Partition:1  Offset:0  c-001  gravado
Partition:1  Offset:1  c-001  corrigido
Partition:1  Offset:2  c-004  gravado
```

É a Aula 7, §4, ao vivo: **mesma chave, mesma partição, ordem preservada** (`c-001` gravado antes de corrigido). Entre partições não há ordem global.

### 3.4 Derrubar um broker

```bash
docker compose stop kafka-1
docker compose exec kafka-2 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-2:9092 --describe --topic comprovante-gravado
```

Saída da nossa execução:

```
    Partition: 0  Leader: 2  Replicas: 2,3,1  Isr: 2,3
    Partition: 1  Leader: 3  Replicas: 3,1,2  Isr: 3,2
    Partition: 2  Leader: 2  Replicas: 1,2,3  Isr: 2,3
```

**O que observar:**

- A partição que tinha o `kafka-1` como líder (na nossa execução, a 2) ganhou **novo líder**, escolhido entre as réplicas do ISR. Quais partições ficam com qual líder muda a cada execução; compare com o seu `describe` do passo 3.2.
- O broker `1` **saiu do ISR** de todas as partições, mas continua em `Replicas`: o lugar dele está guardado para quando voltar.
- Ainda há **2 réplicas em sincronia**, o mínimo exigido. Produza de novo (pelo `kafka-2`) e a escrita com `acks=all` é aceita.

Se o `kafka-1` era o controller ativo, o `describe --status` do 3.1 (rodado no `kafka-2`) mostra outro `LeaderId`.

### 3.5 Derrubar o segundo: `NOT_ENOUGH_REPLICAS`

```bash
docker compose stop kafka-3
docker compose exec kafka-2 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-2:9092 --describe --topic comprovante-gravado
docker compose exec kafka-2 /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka-2:9092 --topic comprovante-gravado --property parse.key=true --property key.separator=: --producer-property acks=all
```

Digite `c-006:gravado`. O produtor responde:

```
NotEnoughReplicasException: Messages are rejected since there are fewer in-sync replicas than required.
```

O ISR caiu para **1** (só o broker `2`), abaixo do `min.insync.replicas=2`. Com `acks=all`, o Kafka **recusa** a escrita em vez de guardar uma cópia única. Agora repita com `--producer-property acks=1` e digite `c-007:gravado`: é aceita. A troca fica assim:

| Produtor | Com só 1 réplica viva | Risco |
|---|---|---|
| `acks=all` + `min.insync.replicas=2` | recusa (`NOT_ENOUGH_REPLICAS`) | indisponibilidade de escrita |
| `acks=1` | aceita | se o único broker perder o disco antes dos outros voltarem, a mensagem some |

> Desde o Kafka 3.0 o produtor Java já vem com `acks=all` e idempotência ligada por padrão. Quem baixa para `acks=1` está escolhendo throughput em troca de durabilidade, e precisa saber disso.

### 3.6 Religar: ISR volta, grupo retoma do offset

```bash
docker compose up -d --wait
docker compose exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --describe --topic comprovante-gravado
docker compose exec kafka-3 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka-3:9092 --topic comprovante-gravado --group notificacao --property print.key=true --property print.partition=true --property print.offset=true --max-messages 2
```

**O que observar:**

- O ISR volta a ter os três brokers: os que voltaram copiaram do líder o que perderam.
- Os líderes **não voltam sozinhos** na hora: na nossa execução, o broker `2` ficou líder das três partições. O Kafka rebalanceia periodicamente; para forçar agora:

  ```bash
  docker compose exec kafka-1 /opt/kafka/bin/kafka-leader-election.sh --bootstrap-server kafka-1:9092 --election-type preferred --all-topic-partitions
  ```

- O consumidor do grupo `notificacao` lê **só** `c-005` e `c-007`, as duas mensagens novas, mesmo conectado a outro broker: o offset é do **grupo** (Aula 7, §3) e ficou guardado no cluster, replicado como qualquer tópico. A `c-006`, recusada no passo 3.5, nunca entrou. Confira:

  ```bash
  docker compose exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092 --describe --group notificacao
  ```

### 3.7 E na aplicação Spring?

Uma aplicação na sua máquina (fora do Docker) usa as portas externas:

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:19092,localhost:29092,localhost:39092
    producer:
      acks: all
```

A lista é só a **porta de entrada**: o cliente pergunta a um dos brokers quem é o líder de cada partição e passa a falar direto com ele. Listar mais de um evita depender de um único nó para a conexão inicial.

Para terminar: `docker compose down -v`.

---

## 4. Lado a lado

| | RabbitMQ (quorum queue) | Kafka |
|---|---|---|
| Unidade replicada | a **fila** | a **partição** |
| Quem atende | o líder da fila | o líder de cada partição (carga espalhada) |
| Escolha do líder | Raft entre os membros da fila | controller (quórum KRaft) escolhe no ISR |
| Escrita segura | *publisher confirm* após maioria | `acks=all` + `min.insync.replicas` |
| Sem maioria / ISR abaixo do mínimo | não confirma | `NOT_ENOUGH_REPLICAS` |
| Na aplicação | `spring.rabbitmq.addresses` | `spring.kafka.bootstrap-servers` |

As duas ferramentas fazem a mesma escolha diante da pane: **param de aceitar escrita** antes de aceitar uma mensagem que existiria numa cópia só. A diferença é de vocabulário, não de princípio.

---

## 5. Para ir além

- **RabbitMQ — Quorum Queues** (documentação oficial): https://www.rabbitmq.com/docs/quorum-queues
- **RabbitMQ — Clustering Guide**: https://www.rabbitmq.com/docs/clustering
- **Apache Kafka — Replication** (seção *Design* da documentação): https://kafka.apache.org/documentation/#replication
- **Apache Kafka — KRaft**: https://kafka.apache.org/documentation/#kraft
