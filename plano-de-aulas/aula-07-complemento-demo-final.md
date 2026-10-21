# Complemento: a demo final do projeto-guia em casa (resiliência, contract testing e clusters)

> **Tempo estimado:** ~1h30 para fazer tudo, ou ~15 min por parte. É a demonstração da aula de 21/10, agora para você repetir no seu computador pessoal. O projeto-guia inteiro (gabarito `aula-08`: emissor, gravador, consulta e notificação) roda sobre um RabbitMQ de 3 nós e um Kafka de 3 brokers. Você liga e desliga falhas, quebra um contrato de propósito derruba nós com tráfego passando, derruba serviços e escala a notificação num consumer group. Antes de cada experimento há uma pergunta. **Responda antes de rodar**: o aprendizado está na diferença entre o que você previu e o que aconteceu.

Leituras de apoio: [`aula-08-aluno.md`](aula-08-aluno.md) (resiliência), [`aula-08-contract-aluno.md`](aula-08-contract-aluno.md) (contract testing) e [`aula-07-complemento-clusters.md`](aula-07-complemento-clusters.md) (clusters sem a aplicação).

---

## 0. Antes de começar

**O que você precisa:**

- **Docker** com Compose v2 (`docker compose version` responde).
- **JDK 21.** O gabarito **não compila no JDK 25**: o erro aparece como `Unsupported class file major version` ou como uma falha do Byte Buddy. Se você tiver mais de um JDK, aponte o 21 com `JAVA21_HOME`, como mostrado mais abaixo.
- **Maven 3.9+** no `PATH`. Em casa, as dependências vêm do Maven Central; não precisa do `settings.xml` do ambiente corporativo.
- **`curl`** e um terminal **bash**. No macOS e no Linux funciona direto. No Windows, use o **WSL 2**, com o Docker Desktop integrado a ele. O Postman (§8) funciona em qualquer sistema.
- **Memória:** são 6 contêineres e 4 JVMs ao mesmo tempo. Numa medição de 21/10/2026, com tudo de pé, foram cerca de 1,5 GB nos contêineres e 0,6 GB nos serviços. Feche o que puder e confira a memória reservada ao Docker Desktop.
- **Portas livres:**
  - RabbitMQ: 5672–5674 e 15672–15674;
  - Kafka: 19092, 29092 e 39092;
  - serviços: 8081 a 8084, e 8085 e 8086 para as instâncias extras da notificação (§7).

  Se o `docker-compose.yml` simples do gabarito estiver de pé, derrube-o antes: `docker compose -p gabarito-pix down`.

**Onde está cada coisa** (em [`projetos/projeto-guiado-pix/gabarito/demo/`](../projetos/projeto-guiado-pix/gabarito/demo/)):

| Arquivo | Para quê |
|---|---|
| `demo.sh` | Sobe e derruba os clusters e os 4 serviços, mostra quem é líder, derruba e religa nós |
| `chamadas.sh` | As chamadas curl deste roteiro: emitir, consultar, ligar falha, ver o circuito |
| `pix-demo-aula-07.postman_collection.json` + `pix-local.postman_environment.json` | As mesmas chamadas no Postman, com testes (importe os dois) |
| `logback-demo.xml` | Devolve os logs dos serviços (o broker embarcado do perfil `plano-b-jvm` traz um `logback.xml` que os silencia) |

**Subir tudo** (todos os comandos deste roteiro rodam de dentro de `demo/`):

```bash
cd projetos/projeto-guiado-pix/gabarito/demo
./demo.sh preparar     # confere Java 21 e Docker, compila o gabarito aula-08
./demo.sh up           # sobe os 2 clusters e os 4 serviços, e mostra o status
```

Na primeira vez, o Docker baixa as imagens e o Maven baixa as dependências, o que pode levar alguns minutos. No fim do `up`, os quatro serviços aparecem como `UP`. Se o Java do seu `PATH` não for o 21, rode assim: `JAVA21_HOME=/caminho/do/jdk-21 ./demo.sh preparar` (e o mesmo no `up`).

Deixe dois terminais extras abertos, acompanhando os logs:

```bash
./demo.sh logs notificacao
./demo.sh logs gravador
```

**Teste rápido:**

```bash
./chamadas.sh emitir       # HTTP 202 e o id do comprovante
./chamadas.sh consultar    # HTTP 200 com o comprovante (usa o último id emitido)
```

> **Pergunta:** quantos serviços esse comprovante atravessou até a notificação aparecer no log? (Resposta: emissor → fila → gravador → tópico → notificação.)

---

## 1. Como a aplicação chega aos clusters (sem mudar código)

Os serviços rodam na sua máquina e recebem a lista de nós por variável de ambiente, exportada pelo `demo.sh`:

```bash
SPRING_RABBITMQ_ADDRESSES=localhost:5672,localhost:5673,localhost:5674
KAFKA=localhost:19092,localhost:29092,localhost:39092
```

Duas decisões são de **operação**, não de código, e as duas estão no `demo.sh`:

- toda fila declarada sem tipo nasce **quorum**, com cópia nos três nós (`rabbitmqctl update_vhost_metadata / --default-queue-type quorum`);
- o tópico `comprovante-gravado` é criado antes, com **3 réplicas**. O gabarito declara `replicas(1)`, mas, como o tópico já existe, o gravador só confere que ele está lá.

> **Pergunta:** quem decide o tipo da fila e o número de cópias: quem escreve o código ou quem opera o broker? O que acontece se cada um achar que é o outro?

---

## 2. Resiliência: retry, DLT, circuit breaker e bulkhead

Abra na IDE o módulo `aula-08/comprovante-notificacao`:

- **`NotificacaoListener`:** `@RetryableTopic` com 4 tentativas, espera de ~1 s dobrando a cada vez e com *jitter* (`random = true`), mais o `@DltHandler`, que chama o `AlertaService`.
- **`NotificacaoService`:** o aviso idempotente (`jaNotificado` / `marcarNotificado`).
- **`GatewayCliente`:** `@Bulkhead` (pool de threads próprio), `@CircuitBreaker` e `@RateLimiter`, com o `fallback` que agenda reenvio.
- **`application.yml`:** o circuito `gatewayNotificacao` (janela de 10, 50% de falha, 30 s aberto) e o rate limiter (100 chamadas por segundo).

O serviço sobe com o perfil `demo`, que acrescenta botões para derrubar ou deixar lento o gateway externo sem editar código (`/demo/gateway`; veja `GatewayDemoController` e `GatewayHttp`).

| # | Faça | Antes de rodar, preveja | O que aparece |
|---|---|---|---|
| 1 | `./chamadas.sh falha on`, `./chamadas.sh emitir 2` e `./chamadas.sh reatores` | O gateway caiu. O retry vai tentar de novo? | `Gateway indisponível (gateway indisponível). Enfileirando para reenvio: <id>`, uma vez por comprovante; `reenvios_pendentes=2`. Nenhum retry, nenhuma DLT |
| 2 | `./chamadas.sh falha propaga`, `./chamadas.sh emitir` e `./chamadas.sh reatores` | E agora, que o fallback devolve a falha? | Quatro tentativas, com intervalos que **variam** (no ensaio: 1,7 s, 2,2 s e 4,9 s); depois `caiu na DLT do tópico de notificação: falha permanente, precisa de olhar humano` e `falhas_permanentes=1` |
| 3 | Emita mais um ou dois e `./chamadas.sh circuito` | Quantas falhas até o circuito abrir? | `failureRate:90.0% … state:OPEN`. Log: `CircuitBreaker 'gatewayNotificacao' is OPEN and does not permit further calls` |
| 4 | `./chamadas.sh falha off`, espere **30 s**, `./chamadas.sh emitir 3` e `./chamadas.sh circuito` | Quem decide que o gateway voltou? | `state:CLOSED`. Depois dos 30 s, as chamadas seguintes passam pelo meio-aberto (3 de teste) e fecham o circuito |
| 5 | `./chamadas.sh lento 2000`, `./chamadas.sh bulkhead 10` e `./chamadas.sh circuito` | O gateway ficou lento, não caiu. O que acontece com 10 chamadas ao mesmo tempo? | `chamadas=10, atendidas=4, enviadas_para_reenvio=6`: o pool tem 2 threads e fila de 2 (perfil `demo`); as outras 6 recebem `Bulkhead 'gatewayNotificacao' is full`. E o circuito **abre** (`failureRate:60.0%`) |
| 6 | `./chamadas.sh falha off` (zera a lentidão) | — | Gateway normal; o circuito fecha sozinho depois de 30 s e das chamadas de teste |

**Para pensar:**

- **No passo 1:** o fallback **degradou com elegância**: agendou o reenvio em vez de derrubar o consumo. A notificação não se perdeu; ficou para depois. E se o reenvio também falhar? Quem olha a fila de reenvio? (`aula-08-aluno.md`, §5.)
- **No passo 2:** por que o intervalo dobra em vez de ser fixo, e por que ele varia? (§3, *retry storm* e *jitter*.) E por que o retry só é seguro porque o consumidor é idempotente? (§4.)
- **No passo 3:** com o circuito aberto, quem deixou de sofrer: o gateway ou a gente?
- **No passo 5:** o circuito contou as recusas do bulkhead como falha. Isso é bom ou ruim? Num gateway lento, faz sentido parar de chamar por 30 s?
- **Onde estão os tópicos de retry?** Rode `kafka-topics.sh --list` dentro de um broker: `comprovante-gravado-retry-0`, `-retry-1`, `-retry-2` e `-dlt`. Com *jitter*, o atraso não serve de nome, e o código usa o sufixo por índice (`topicSuffixingStrategy`).

---

## 3. Contract testing: HTTP e mensagens

Em outro terminal, entre em `projetos/projeto-guiado-pix/gabarito/aula-08`, com o JDK 21 no `JAVA_HOME`. São três contratos, cada um com um consumidor (gera o pact) e um provedor (verifica):

| Contrato | Consumidor (gera) | Provedor (verifica) |
|---|---|---|
| HTTP `GET /comprovantes/{id}` | `comprovante-consulta`: `GravadorContractTest` (2 interações: existe e não existe) | `comprovante-gravador`: `GravadorVerificacaoTest` |
| Evento `comprovante-gravado` (Kafka) | `comprovante-notificacao`: `EventoGravadoContractTest` | `comprovante-gravador`: `EventoGravadoVerificacaoTest` |
| Comando da fila `gravacao.q` (RabbitMQ) | `comprovante-gravador`: `ComandoGravacaoContractTest` | `comprovante-emissor`: `ComandoGravacaoVerificacaoTest` |

```bash
mvn test | grep -E "Tests run:|BUILD"
```

Todos passam: 2 testes na consulta, 1 na notificação, 1 no emissor e 4 no gravador. O teste HTTP do gravador sobe o Spring e pode levar perto de 1 minuto. O consumidor grava o pact em `target/pacts/`; o provedor lê de `src/test/resources/pacts/` (num time real, o Pact Broker faz esse transporte).

**Agora quebre de propósito o contrato HTTP:** a consulta passa a exigir um campo que o gravador não devolve.

```bash
P=comprovante-gravador/src/test/resources/pacts/comprovante-consulta-comprovante-gravador.json
cp "$P" /tmp/pact-original.json
# no editor: no body da resposta, depois de "chavePix": "joao@pix.com", acrescente  "tipoDocumento": "CPF",
mvn -pl comprovante-gravador test -Dtest=GravadorVerificacaoTest | grep -E "Tests run|BUILD|missing"
cp /tmp/pact-original.json "$P"       # restaura
```

> **Antes de rodar, preveja:** o teste de quem vai falhar, o da consulta ou o do gravador? Rodando: `body: $ Actual map is missing the following keys: tipoDocumento` e `Failures: 1`, no build do **provedor**, antes de qualquer deploy.

**Um caso real deste gabarito.** Até 21/10, todo comprovante do projeto-guia ia para a DLQ. Na aula 4, que ainda não tinha DLQ, a mensagem ficava em laço. A causa era o formato:
- o emissor publicava o comando na fila em `snake_case` (`numero_agencia`);
- o gravador lia em `camelCase` (`numeroAgencia`), e o id chegava nulo.

O contrato HTTP tinha PACT; o da **mensagem** emissor→gravador não tinha nenhum. A correção foi uma linha no `MessagingConfig` do gravador. Hoje o gabarito tem o *message pact* do comando. Reproduza o defeito e veja o build pegar:

```bash
# no editor, em comprovante-gravador/.../MessagingConfig.java, troque o conversor por
#   return new Jackson2JsonMessageConverter(mapper);
mvn -pl comprovante-gravador test -Dtest=ComandoGravacaoContractTest | grep -E "Tests run|BUILD|Expecting"
# desfaça a troca
```

Rodando: `Expecting actual not to be null` e `Failures: 1`. O defeito que derrubou o projeto-guia vira um build vermelho.

> **Pergunta:** no seu projeto de grupo, quais contratos de mensagem estão sem teste?

---

## 3.1 Um fato, quatro grupos: notificação, antifraude, BI e compliance

O gravador publica **um** evento por comprovante. No módulo de notificação, quatro consumer groups o leem, cada um com o seu offset (`aula-07-aluno.md`, passos 3 e 4): `NotificacaoListener`, `AntifraudeListener`, `BiListener` e `ComplianceListener`.

| # | Faça | Antes de rodar, preveja | O que aparece |
|---|---|---|---|
| 1 | `./chamadas.sh emitir 2` e `./demo.sh logs notificacao` | Quantas linhas por comprovante? | Uma de cada reator: `Enviando notificação…`, `Antifraude avaliando o padrão…` e `BI: N comprovante(s), total …` |
| 2 | `./chamadas.sh reatores` e `./demo.sh grupo bi` | O compliance já viu algum? | `compliance_arquivados=0`: o grupo `compliance` nasce **desligado** e ainda nem existe no Kafka |
| 3 | `./demo.sh compliance` e, depois de uns 10 s, `./chamadas.sh reatores` | O compliance vai ver só o que chegar daqui para frente? | `compliance_arquivados` igual a **todos** os comprovantes já gravados: grupo novo, com `auto-offset-reset: earliest`, lê o log desde o início |

**Para pensar:**

- **Adicionar o compliance não mexeu no gravador.** O sistema cresceu por adição, não por modificação.
- **No passo 3, o BI zerou** (`bi_quantidade=0`): a notificação reiniciou e a contagem vivia em memória. Mas o grupo `bi` **não** releu o histórico: o offset dele estava guardado no Kafka. Onde a volumetria precisaria morar para sobreviver ao reinício?

---

## 4. Clusters com a aplicação: RabbitMQ

Antes, confira a topologia:

```bash
./demo.sh lideres
```

A fila `gravacao.q` é `quorum` e tem um líder e três membros. Anote quem é o líder.

| # | Faça | Antes de rodar, preveja | O que aparece |
|---|---|---|---|
| 1 | `./demo.sh derruba rabbit N`, com N = o líder, e depois `./chamadas.sh emitir 2` e `./chamadas.sh consultar` | Com o líder fora, o PIX para? | 202, 202 e 200. Outro nó vira líder (`./demo.sh lideres`). No log do emissor, um `Connection reset` seguido de reconexão automática |
| 2 | Derrube mais um nó (sobra 1 de 3) e depois `./chamadas.sh emitir` e `./chamadas.sh consultar` | Sem maioria, o emissor recusa o comprovante? | **202 no emissor**, mas a consulta dá **404**. Nenhum erro no log |
| 3 | `./demo.sh levanta rabbit X` para os dois nós e depois `./chamadas.sh consultar` | E agora? | **200**: a mensagem ficou retida e foi gravada quando a maioria voltou |
| 4 | Derrube os três nós e depois `./chamadas.sh emitir` e `./chamadas.sh saude` | E com zero nós, o emissor ainda diz "aceito"? | **HTTP 500**, sem id. O `health` do emissor e do gravador passa a `DOWN` |
| 5 | `./demo.sh levanta rabbit todos` e depois `./chamadas.sh emitir` e `./chamadas.sh consultar` | Dá para religar um nó por vez? | Os três sobem juntos em uns 15 s; depois, 202 e 200, sem reiniciar nenhum serviço |

**Para pensar:**

- **No passo 2:** o emissor respondeu "aceito" sem a fila ter confirmado. Por quê? Ele não usa *publisher confirms* (Aula 6).
- **No passo 3:** deu certo, mas era garantido? Sem confirmação do broker, o resultado é **indefinido**. Funcionou aqui; não é promessa.
- **Nos passos 2 e 4:** com 1 nó o sistema **mente** (diz "aceito" sem a fila confirmar); com 0 nós ele **falha à vista** (500). Qual dos dois é pior para o cliente do PIX?
- **No passo 5:** depois de uma queda total, cada nó espera os outros para remontar o cluster. Se você religar só um (`./demo.sh levanta rabbit 1`), o comando fica parado, esperando. Não é defeito: o nó não sabe se a cópia dele é a mais nova. Experimente e destrave com `./demo.sh levanta rabbit todos` em outro terminal.

---

## 5. Clusters com a aplicação: Kafka

```bash
./demo.sh lideres
```

São 3 partições, cada uma com um líder, e o `Isr` (as réplicas em dia) lista os três brokers.

| # | Faça | Antes de rodar, preveja | O que aparece |
|---|---|---|---|
| 1 | `./demo.sh derruba kafka 1` e `./chamadas.sh emitir 2` | Qual partição troca de líder? O consumer group perde o lugar? | As notificações saem normalmente. A partição que era do broker 1 ganha outro líder. O offset é do grupo e está replicado |
| 2 | `./demo.sh derruba kafka 3` (sobra 1 broker) e `./chamadas.sh emitir` e `./chamadas.sh consultar` | Com 1 broker vivo e `min.insync.replicas=2`, o que acontece? | **200 na consulta** (gravou no banco), mas **nenhuma notificação**. No log do gravador, `NOT_ENOUGH_REPLICAS` repetido |
| 3 | `./demo.sh levanta kafka 3` **em menos de 2 minutos** | O evento se perdeu? | A notificação aparece segundos depois |
| 4 | `./demo.sh levanta kafka 1` | — | O cluster volta inteiro |

**Para pensar:**

- **No passo 2:** o comprovante está gravado e o evento **não saiu**. Por quanto tempo o produtor insiste? (`delivery.timeout.ms`, 120 s por padrão.) E se passasse disso? Experimente: repita o passo 2 e espere mais de 2 minutos antes de religar.
- **É o problema da escrita dupla (*dual write*):** gravar no banco e publicar no tópico não é uma operação atômica. Se o produtor desistir, o banco diz "gravado" e ninguém é notificado. A saída clássica é o **outbox pattern**. No seu projeto de grupo, onde há escrita dupla?

**Comparando os dois brokers:** o RabbitMQ e o Kafka preferem **parar de aceitar escrita** a aceitar uma mensagem que existiria numa cópia só. Compare com a tabela do [complemento de clusters, §4](aula-07-complemento-clusters.md).

---

## 6. Um serviço cai, os outros seguem

Até aqui caíram os brokers. Agora cai a **aplicação**, com os brokers inteiros. O broker guarda o que chegou e entrega quando o consumidor volta: na fila, a mensagem espera; no tópico, o evento fica no log e o consumidor retoma do **offset** do grupo.

### 6.1 O gravador cai (fila)

| # | Faça | Antes de rodar, preveja | O que aparece |
|---|---|---|---|
| 1 | `./demo.sh derruba gravador` e `./chamadas.sh emitir 3` | O emissor percebe que o gravador caiu? | Três **202**. O emissor só fala com a fila |
| 2 | `./demo.sh lideres` e `./chamadas.sh consultar` | Onde estão os 3 comprovantes? | A `gravacao.q` mostra **3** mensagens. A consulta dá **500**: ela lê o gravador por HTTP, e ele está fora |
| 3 | `./demo.sh levanta gravador`, `./demo.sh lideres` e `./chamadas.sh consultar` | E agora? | A fila volta a **0**, a consulta dá **200** e as 3 notificações aparecem no log |

**Para pensar:**

- **Fila e HTTP se comportam diferente.** O emissor, que usa fila, não sentiu a queda. A consulta, que usa HTTP, devolveu erro. Qual dos dois é *temporal coupling* (acoplamento no tempo)?
- **Um efeito colateral, de propósito:** o gravador guarda os comprovantes num banco **em memória** (H2). Consulte um id emitido **antes** da queda direto no gravador: `./chamadas.sh interno <id>` dá **404**, porque o banco foi zerado no reinício. A consulta ainda responde 200 para ele porque guardou o comprovante no cache. Num sistema real, onde esse estado precisaria morar?

### 6.2 A notificação cai (tópico e offset)

Antes, emita alguns comprovantes para que as 3 partições já tenham offset registrado no grupo:

```bash
./chamadas.sh emitir 6
./demo.sh grupo        # partição, offset do grupo, fim do log, lag e instância que lê
```

| # | Faça | Antes de rodar, preveja | O que aparece |
|---|---|---|---|
| 1 | `./demo.sh derruba notificacao` e `./chamadas.sh emitir 4` | O gravador para de publicar? | Quatro **202**, e a consulta dá **200**: gravar não depende da notificação |
| 2 | `./demo.sh grupo` | Onde estão os 4 eventos? | `Consumer group 'notificacao' has no active members` e o **lag** distribuído entre as partições (no ensaio: 1, 3 e 0). Os eventos estão no log; o grupo só ainda não leu |
| 3 | `./demo.sh levanta notificacao` e, depois de uns 10 s, `./demo.sh grupo` | Ela vai reprocessar tudo desde o começo? | Só os **4** eventos novos aparecem no log, e o lag volta a **0**. O consumidor retomou do offset do grupo |

> **Pergunta:** o offset é de quem: da instância, do grupo ou do tópico? (`aula-07-aluno.md`.) E se você quisesse reprocessar tudo desde o início, o que mudaria?

---

## 7. Consumer group: várias instâncias da notificação

O tópico `comprovante-gravado` tem **3 partições**. Instâncias do **mesmo** grupo dividem as partições entre si: cada partição é lida por **uma** instância de cada vez.

```bash
./demo.sh escala 3      # sobe notificacao-2 (porta 8085) e notificacao-3 (porta 8086) e mostra o grupo
```

| # | Faça | Antes de rodar, preveja | O que aparece |
|---|---|---|---|
| 1 | `./demo.sh escala 3` | Como as 3 partições se dividem entre 3 instâncias? | Uma partição para cada instância (`notificacao`, `notificacao-2` e `notificacao-3`) |
| 2 | `./chamadas.sh emitir 9` e `grep -c "Enviando notificação" .run/notificacao*.log` | Cada evento é notificado quantas vezes? | Cada evento, **uma vez só**, numa das instâncias. No ensaio: 4, 4 e 1, de acordo com a partição de cada comprovante (a chave é o id) |
| 3 | `./demo.sh derruba notificacao-2`, espere uns 10 s e `./demo.sh grupo` | O que acontece com a partição dela? | **Rebalanceamento:** a partição passa para outra instância, e os eventos novos continuam sendo notificados |
| 4 | `./demo.sh escala 1` | — | Volta a uma instância, que fica com as 3 partições |

**Para pensar:**

- **E com 4 instâncias e 3 partições?** Uma fica **parada**. O número de partições é o teto do paralelismo de um grupo.
- **E um segundo grupo?** Antifraude e BI (`aula-07-aluno.md`) seriam **outros** grupos no mesmo tópico: cada grupo recebe **todos** os eventos, com o seu próprio offset. Dentro de um grupo, divide-se o trabalho; entre grupos, replica-se o fato.
- **No RabbitMQ, a ideia equivalente** são os consumidores concorrentes numa fila ([complemento da Aula 5](aula-05-complemento-consumidores-concorrentes.md)). Qual é a diferença na ordem das mensagens?

---

## 8. No Postman

Importe os dois arquivos de `demo/` (*Import* → arraste a collection e o environment) e selecione o environment **PIX local (demo)**. As pastas seguem este roteiro:

- **0. Saúde:** o health dos 4 serviços.
- **1. Caminho feliz:**
  - emitir (guarda o id numa variável);
  - consultar;
  - ler direto no gravador;
  - emitir com agência inválida, que dá 400.
- **2. Resiliência (perfil demo):** ver o estado do gateway, ligar a falha (com reenvio agendado ou propagada até a DLT), deixar o gateway lento e disparar a rajada do bulkhead, voltar ao normal, ver o estado e os eventos do circuito e os contadores dos reatores.

Cada requisição tem testes; rode a collection inteira pelo *Runner* com tudo de pé. A collection não derruba nós nem serviços: para as partes 3.1 e 4 a 7, use o `demo.sh`.

---

## 9. Ao terminar

```bash
./demo.sh down          # para os 4 serviços e derruba os clusters, apagando os dados
docker ps               # não deve sobrar nenhum contêiner cluster-*
```

---

## 10. Se algo der errado

| Sintoma | O que fazer |
|---|---|
| `./demo.sh levanta rabbit N` parado em `Waiting` depois de derrubar os três nós | Em outro terminal: `./demo.sh levanta rabbit todos` (o comando parado se destrava sozinho). No Kafka, para religar os três brokers juntos: `./demo.sh levanta kafka todos` |
| `./demo.sh grupo` não mostra o lag de uma partição | A partição ainda não tem offset registrado no grupo: emita alguns comprovantes com a notificação de pé antes de derrubá-la |
| `porta 5672/15672/9092 ocupada` no `up` | `docker compose -p gabarito-pix down`; ou `./demo.sh down` e `./demo.sh up` |
| `o gabarito precisa do JDK 21` no `preparar` | Instale o JDK 21 e rode com `JAVA21_HOME=/caminho/do/jdk-21` |
| `Unsupported class file major version` ou erro de Byte Buddy no `mvn` | O Maven rodou com outro JDK: `export JAVA_HOME=/caminho/do/jdk-21` antes |
| Serviço `FORA` no `./demo.sh status` | `./demo.sh logs <serviço>`; depois `./demo.sh apps-down` e `./demo.sh apps-up` |
| Log da notificação mudo | Suba pelo `demo.sh`, que passa o `logback-demo.xml` |
| Teste HTTP do provedor (PACT) demora | É esperado: ele sobe o Spring do gravador e leva perto de 1 minuto, com ou sem brokers |
| `Did not find a test class method annotated with @State(...)` | O pact em `target/pacts/` acumulou interações antigas: apague `target/pacts/`, rode o consumidor de novo e copie o arquivo para `src/test/resources/pacts/` |
| O circuito não abre | A janela é de 10 chamadas, com 50% de falha. Emita mais com `falha propaga`: cada comprovante gera 4 chamadas |
| O circuito não fecha | Ele fica aberto 30 s e só passa a meio-aberto quando chega uma chamada: emita alguns comprovantes depois dos 30 s. Confira se a falha e a lentidão estão desligadas (`./chamadas.sh falha off`) |
| `compliance_arquivados=0` depois do `./demo.sh compliance` | Espere uns 10 s (o grupo novo precisa entrar e ler o tópico) e repita `./chamadas.sh reatores` |
| Contêiner reiniciando ou máquina lenta | Falta memória: feche aplicações, aumente a memória do Docker Desktop ou faça uma parte por vez (`./demo.sh clusters-up` sobe só os clusters) |
| No Windows, `bad interpreter` ou `\r` nos scripts | O Git converteu as quebras de linha. Dentro do WSL: `sed -i 's/\r$//' demo.sh chamadas.sh` |
