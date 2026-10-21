# Complemento: a demo final do projeto-guia em casa (resiliência, contract testing e clusters)

> **Tempo estimado:** ~1h para fazer tudo, ou ~20 min por parte. É a demonstração da aula de 21/10, agora para você repetir no seu computador pessoal. O projeto-guia inteiro (gabarito `aula-08`: emissor, gravador, consulta e notificação) roda sobre um RabbitMQ de 3 nós e um Kafka de 3 brokers. Você liga e desliga falhas, quebra um contrato de propósito e derruba nós com tráfego passando. Antes de cada experimento há uma pergunta. **Responda antes de rodar**: o aprendizado está na diferença entre o que você previu e o que aconteceu.

Leituras de apoio: [`aula-08-aluno.md`](aula-08-aluno.md) (resiliência), [`aula-08-contract-aluno.md`](aula-08-contract-aluno.md) (contract testing) e [`aula-07-complemento-clusters.md`](aula-07-complemento-clusters.md) (clusters sem a aplicação).

---

## 0. Antes de começar

**O que você precisa:**

- **Docker** com Compose v2 (`docker compose version` responde).
- **JDK 21.** O gabarito **não compila no JDK 25**: o erro aparece como `Unsupported class file major version` ou como uma falha do Byte Buddy. Se você tiver mais de um JDK, aponte o 21 com `JAVA21_HOME`, como mostrado mais abaixo.
- **Maven 3.9+** no `PATH`. Em casa, as dependências vêm do Maven Central; não precisa do `settings.xml` do ambiente corporativo.
- **`curl`** e um terminal **bash**. No macOS e no Linux funciona direto. No Windows, use o **WSL 2**, com o Docker Desktop integrado a ele. O Postman (§6) funciona em qualquer sistema.
- **Memória:** são 6 contêineres e 4 JVMs ao mesmo tempo. Numa medição de 21/10/2026, com tudo de pé, foram cerca de 1,5 GB nos contêineres e 0,6 GB nos serviços. Feche o que puder e confira a memória reservada ao Docker Desktop.
- **Portas livres:**
  - RabbitMQ: 5672–5674 e 15672–15674;
  - Kafka: 19092, 29092 e 39092;
  - serviços: 8081 a 8084.

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

## 2. Resiliência: retry, DLT e circuit breaker

Abra na IDE o módulo `aula-08/comprovante-notificacao`:

- **`NotificacaoListener`:** `@RetryableTopic` com 4 tentativas e espera de 1 s, dobrando a cada vez, mais o `@DltHandler`.
- **`NotificacaoGateway`:** `@CircuitBreaker` com `fallbackMethod`.
- **`application.yml`:** a configuração do circuito (`resilience4j.circuitbreaker`).

O serviço sobe com o perfil `demo`, que acrescenta um botão para simular a queda do gateway externo sem editar código (`POST /demo/gateway`; veja `GatewayDemoController`).

| # | Faça | Antes de rodar, preveja | O que aparece |
|---|---|---|---|
| 1 | `./chamadas.sh falha on` e `./chamadas.sh emitir 2` | O gateway caiu. O retry vai tentar de novo? | Log da notificação: `Fallback de notificação …: gateway indisponível` e **mais nada**. Nenhum retry, nenhuma DLT |
| 2 | `./chamadas.sh falha propaga` e `./chamadas.sh emitir` | E agora, que o fallback relança a exceção? | Quatro tentativas, com 1 s, 2 s e 4 s de intervalo, e depois a linha `caiu na DLT do tópico de notificação` |
| 3 | `./chamadas.sh circuito`; emita mais um ou dois e repita | Quantas falhas até o circuito abrir? | Muda de `state:CLOSED` para `state:OPEN`, com a taxa de falha acima de 50%. Log: `CircuitBreaker 'notificacao-gateway' is OPEN and does not permit further calls` |
| 4 | `./chamadas.sh falha off`, espere ~10 s, `./chamadas.sh emitir 3` e `./chamadas.sh circuito` | Quem decide que o gateway voltou? | Volta a `state:CLOSED`. Passou pelo meio-aberto, com algumas chamadas de teste |

**Para pensar:**

- **No passo 1:** o fallback devolveu um valor padrão e a falha **sumiu**. O offset andou e a notificação se perdeu. Isso é resiliência ou é esconder erro? Num GET de catálogo, a resposta degradada é aceitável; numa notificação de PIX, a falha precisa voltar para o retry e para a DLT (`aula-08-aluno.md`, §5 e §7).
- **No passo 2:** por que o intervalo dobra em vez de ser fixo? (§3, *retry storm*.) E por que o retry só é seguro porque o consumidor é idempotente? (§4.)
- **No passo 3:** com o circuito aberto, quem deixou de sofrer: o gateway ou a gente?
- **Onde estão os tópicos de retry?** Rode `./demo.sh lideres` ou o `kafka-topics.sh --list` dentro de um broker e procure por `comprovante-gravado-retry-1000`, `-retry-2000`, `-retry-4000` e `-dlt`.

---

## 3. Contract testing: o PACT consulta ↔ gravador

Em outro terminal, entre em `projetos/projeto-guiado-pix/gabarito/aula-08`, com o JDK 21 no `JAVA_HOME`. Deixe os clusters de pé: o teste do provedor sobe o contexto Spring do gravador, que conecta nos brokers.

```bash
# 1. Consumidor (consulta): declara o que precisa e gera o pact
mvn -pl comprovante-consulta test | grep -E "Tests run|BUILD"
cat comprovante-consulta/target/pacts/comprovante-consulta-comprovante-gravador.json

# 2. Provedor (gravador): verifica o pact contra o serviço real
mvn -pl comprovante-gravador test | grep -E "Tests run|BUILD|missing"
```

Os dois mostram `Tests run: 1, Failures: 0`. O teste do provedor sobe o Spring e pode levar perto de 1 minuto.

**Agora quebre de propósito:** a consulta passa a exigir um campo que o gravador não devolve.

```bash
P=comprovante-gravador/src/test/resources/pacts/comprovante-consulta-comprovante-gravador.json
cp "$P" /tmp/pact-original.json
# no editor: no body da resposta, depois de "nome": "Giovanni", acrescente  "tipoDocumento": "CPF",
mvn -pl comprovante-gravador test | grep -E "Tests run|BUILD|missing"
cp /tmp/pact-original.json "$P"       # restaura
```

> **Antes de rodar, preveja:** o teste de quem vai falhar, o da consulta ou o do gravador? Rodando: `body: $ Actual map is missing the following keys: tipoDocumento` e `Failures: 1`, no build do **provedor**, antes de qualquer deploy.

**Um caso real deste gabarito.** Até 21/10, todo comprovante do projeto-guia ia para a DLQ. Na aula 4, que ainda não tinha DLQ, a mensagem ficava em laço. A causa era o formato:
- o emissor publicava o comando na fila em `snake_case` (`numero_agencia`);
- o gravador lia em `camelCase` (`numeroAgencia`), e o id chegava nulo.

O contrato HTTP consulta↔gravador tinha PACT; o contrato da **mensagem** emissor→gravador não tinha nenhum. A correção foi uma linha no `MessagingConfig` do gravador (veja nas aulas 4 a 8).

> **Pergunta:** que teste teria pegado esse defeito no build? (Um *message pact*, `aula-08-contract-aluno.md` §8.) No seu projeto de grupo, quais contratos de mensagem estão sem teste?

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

**Para pensar:**

- **No passo 2:** o emissor respondeu "aceito" sem a fila ter confirmado. Por quê? Ele não usa *publisher confirms* (Aula 6).
- **No passo 3:** deu certo, mas era garantido? Sem confirmação do broker, o resultado é **indefinido**. Funcionou aqui; não é promessa.

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

## 6. No Postman

Importe os dois arquivos de `demo/` (*Import* → arraste a collection e o environment) e selecione o environment **PIX local (demo)**. As pastas seguem este roteiro:

- **0. Saúde:** o health dos 4 serviços.
- **1. Caminho feliz:**
  - emitir (guarda o id numa variável);
  - consultar;
  - ler direto no gravador;
  - emitir com agência inválida, que dá 400.
- **2. Resiliência (perfil demo):** ver o estado do gateway, ligar a falha (engolida ou propagada), voltar ao normal, e ver o estado e os eventos do circuito.

Cada requisição tem testes; rode a collection inteira pelo *Runner* com tudo de pé. A collection não derruba nós: para as partes 4 e 5, use o `demo.sh`.

---

## 7. Ao terminar

```bash
./demo.sh down          # para os 4 serviços e derruba os clusters, apagando os dados
docker ps               # não deve sobrar nenhum contêiner cluster-*
```

---

## 8. Se algo der errado

| Sintoma | O que fazer |
|---|---|
| `porta 5672/15672/9092 ocupada` no `up` | `docker compose -p gabarito-pix down`; ou `./demo.sh down` e `./demo.sh up` |
| `o gabarito precisa do JDK 21` no `preparar` | Instale o JDK 21 e rode com `JAVA21_HOME=/caminho/do/jdk-21` |
| `Unsupported class file major version` ou erro de Byte Buddy no `mvn` | O Maven rodou com outro JDK: `export JAVA_HOME=/caminho/do/jdk-21` antes |
| Serviço `FORA` no `./demo.sh status` | `./demo.sh logs <serviço>`; depois `./demo.sh apps-down` e `./demo.sh apps-up` |
| Log da notificação mudo | Suba pelo `demo.sh`, que passa o `logback-demo.xml` |
| Teste do provedor (PACT) trava ao subir | Os brokers estão fora do ar: `./demo.sh status` |
| O circuito não abre | A janela é de 10 chamadas, com 50% de falha. Emita mais com `falha propaga`: cada comprovante gera 4 chamadas |
| Contêiner reiniciando ou máquina lenta | Falta memória: feche aplicações, aumente a memória do Docker Desktop ou faça uma parte por vez (`./demo.sh clusters-up` sobe só os clusters) |
| No Windows, `bad interpreter` ou `\r` nos scripts | O Git converteu as quebras de linha. Dentro do WSL: `sed -i 's/\r$//' demo.sh chamadas.sh` |
