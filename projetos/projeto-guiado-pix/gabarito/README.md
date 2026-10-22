# Gabarito Evolutivo — Comprovantes PIX (BE-JV-010)

Estado **final da aplicação a cada aula**, em pastas independentes. Cada `aula-0X/` é um
projeto Maven completo e **compila** (perfil `plano-b-jvm`, sem Docker). Use para estudar
antes da aula e para **colar o incremento** se o live coding travar (rede do Teams).

> Diferença para `../` (o esqueleto): o esqueleto tem `// TODO` para preencher ao vivo; aqui está **preenchido**.

| Aula | Pasta | Incremento | Verificação |
|---|---|---|---|
| 1 | `aula-01/` | DDD: 3 serviços, bases segregadas, validação no emissor | `compile` ✅ |
| 2 | `aula-02/` | SAGA orquestrada (síncrona) + idempotência + compensação | `compile` ✅ |
| 3 | `aula-03/` | Cache na consulta (Caffeine) + 3 retentativas → 404 | `compile` ✅ |
| 4 | `aula-04/` | Assíncrono: emissor publica, gravador consome (@RabbitListener) | `compile` ✅ |
| 5 | `aula-05/` | Topologia formal + retry + **DLQ** | `compile` ✅ |
| 6 | `aula-06/` | Kafka: tópico de evento + módulo `comprovante-notificacao` | `compile` ✅ |
| 7 | `aula-07/` | Resiliência: `@RetryableTopic` + circuit breaker (Resilience4j) | `compile` ✅ |
| 8 | `aula-08/` | Contract testing (PACT): HTTP consulta↔gravador e message pacts do evento e do comando | `mvn test`: 8 testes ✅ |

Cada pasta tem um **`ESTADO.md`** descrevendo o que funciona naquele ponto e como rodar.

> **Correção de 21/10/2026 (aulas 4 a 8):** o emissor publica o comando na fila em `snake_case` e o gravador
> lia em `camelCase`; o id chegava nulo e todo comprovante ia para a DLQ (na aula 4, sem DLQ, ficava em laço).
> O `MessagingConfig` do gravador agora lê a fila em `snake_case`. Detalhes em `aula-08/ESTADO.md`.

> **Alinhamento ao material (21/10/2026, aulas 1 a 8):** o gabarito passou a seguir o código do material do aluno
> (modelo `Comprovante` + `ChavePix` + `Dinheiro`, SAGA com `INVALIDADO`, `ConsultaComprovante`, `EmissorDeComprovante`,
> retry por interceptor, antifraude/BI/compliance, bulkhead e rate limiter, message pacts). Detalhes no `ESTADO.md` de cada aula.

> **Demo final em casa (com Docker):** a pasta [`demo/`](demo/) sobe o `aula-08` inteiro sobre os clusters de
> RabbitMQ e Kafka, com scripts, chamadas curl e collection do Postman. Roteiro:
> [`aula-07-complemento-demo-final.md`](../../../plano-de-aulas/aula-07-complemento-demo-final.md).

## Como usar
```bash
cd aula-05
mvn -s ../../../../ambiente/settings.xml -Pplano-b-jvm clean compile
# subir um serviço:
mvn -s ../../../../ambiente/settings.xml -Pplano-b-jvm -pl comprovante-gravador spring-boot:run
```

## Rodar localmente com Docker (uso do docente)

> Só para a **sua máquina local** preparar/demonstrar o fluxo ponta a ponta. **A turma não usa Docker** — continua no `plano-b-jvm`. Aqui você usa o perfil **`plano-a-docker`** contra brokers reais.

Arquivos na raiz do gabarito: **`docker-compose.yml`** (Redis + RabbitMQ + Kafka), **`Dockerfile`** (containerizar um serviço) e **`.dockerignore`**.

```bash
# 1) subir os brokers (na raiz do gabarito)
docker compose up -d                 # ou: docker compose up -d redis rabbitmq  (subset por aula)

# 2) rodar os serviços da aula no HOST, com o perfil Docker (resolve do Maven Central local)
cd aula-06
mvn -Pplano-a-docker -pl comprovante-gravador spring-boot:run
#   (-Pplano-a-docker desativa o plano-b-jvm padrão e usa Redis/RabbitMQ/Kafka reais)

docker compose down -v               # ao terminar
```

| Aula | Brokers necessários |
|---|---|
| 1, 2 | nenhum (só H2) |
| 3 | `redis` |
| 4, 5 | `redis` + `rabbitmq` |
| 6, 7 | `redis` + `rabbitmq` + `kafka` |
| 8 | nenhum (PACT em disco) |

- **Painel do RabbitMQ:** http://localhost:15672 (guest/guest).
- **Containerizar um serviço** (opcional): `docker build -f ../Dockerfile --build-arg MODULE=comprovante-emissor -t pix-emissor .` (ver cabeçalho do `Dockerfile`). O caminho mais simples continua sendo rodar os serviços no host via IDE/Maven; rodá-los como container exige apontar `SPRING_*` para os nomes de serviço e ajustar o listener anunciado do Kafka.
- Se um serviço tiver um *bean* que sobe o **broker embarcado** (Qpid/EmbeddedKafka) no código principal, ele deve estar anotado `@Profile("!plano-a-docker")` para não conflitar com os brokers reais.

## O que está verificado e o que não está
- **Verificado:** todas as aulas **compilam** no perfil `plano-b-jvm` (aula 8 também passa nos 8 testes de contrato, `mvn test`).
- **Não verificado em runtime aqui:** o fluxo ponta a ponta das aulas 4–7 exige **broker no ar**
  (Qpid/EmbeddedKafka in-JVM). O código está correto e idiomático; subir os brokers depende de o
  **Nexus Caixa publicar os artefatos** (ver nota abaixo). Se faltarem, o tema cai no `plano-c-conceitual`.

## ⚠️ Nota de ambiente — confirmado Plano B (sem Docker); pendência no Nexus
A Aula 1 confirmou **sem Docker e sem sandbox** → o gabarito roda no perfil **`plano-b-jvm`** (pura-JVM);
`plano-a-docker` fica só como **referência de produção**, não é usado nesta turma. **Pendência aberta:**
o `org.apache.qpid:qpid-broker` arrasta **`com.sleepycat:je`** (store BDB, licença restrita) que **pode
não existir no Nexus Caixa**. Já **excluímos** essa dependência nos `pom.xml` (Qpid roda com **store em
memória**, suficiente para um broker efêmero de aula). **Falta confirmar no Nexus** que o `qpid-broker`
(sem o `je`) e o `spring-kafka-test` resolvem — ver `ambiente/relatorio-semana-0.md` e
`ambiente/checklist-semana-0.md` (na raiz do módulo).
