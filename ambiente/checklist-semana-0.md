# Checklist Semana 0 — Descoberta do Ambiente (BE-JV-010 · Caixa)

> **Turma 1751 — tarefa prévia (D-3, antes de 09/09).** Rode as seções abaixo na sua máquina da rede corporativa e devolva o resultado (print ou texto) no canal da turma. Na 1ª oferta este checklist foi feito ao vivo e consumiu a aula — desta vez ele vem antes. Já sabido pela turma anterior (mesma rede): **sem Docker/sandbox → Plano B (pura-JVM)**; o que precisa de confirmação é a **seção 3** (artefatos de fallback no Nexus). Consolidação em `relatorio-semana-0.md`.

**Quando:** antes da Aula 1 (09/09); consolidação de 10 min na abertura.
**Por quê:** define, **por tema**, se vale o **Plano B (restrito/pura-JVM)** ou o **Plano C (conceitual)** — o Plano A (Docker) já foi descartado. Sem fechar a seção 3, o hands-on de filas/tópicos (Aulas 4–7) fica no escuro.
**Saída:** preencher `relatorio-semana-0.md` com os resultados e o veredito por tema.

> Regra de ouro do ambiente: **se a turma vai baixar, vem do Nexus.** Distribua o `settings.xml` deste diretório (com URL/credenciais reais) antes de qualquer build.

---

## 1. Infra base (bloqueante)

| # | Verificação | Comando / como | Resultado esperado |
|---|---|---|---|
| 1.1 | Java 21+ | `java -version` | `21` ou superior |
| 1.2 | Maven | `mvn -version` | qualquer 3.8+ resolvendo do Nexus |
| 1.3 | `settings.xml` aponta p/ Nexus | `mvn -s settings.xml help:effective-settings` | mirror `*` → Nexus |
| 1.4 | Build offline resolve | `mvn -s settings.xml -o ... ` após 1 build online | sem ir ao Maven Central |

## 2. Docker / contêineres — ✅ RESOLVIDO (sem Docker → Plano B)

Confirmado na Aula 1: **não há Docker nem sandbox** na rede Caixa. Plano A descartado; **Plano B (pura-JVM) é o piso**. Seção mantida só como registro.

| # | Verificação | Resultado |
|---|---|---|
| 2.1 | Docker existe e roda | ❌ NÃO |
| 2.2 | Registry interno c/ imagens | ❌ não aplicável |
| 2.3 | Runtime alternativo (Podman) | ❌ não disponível |

## 3. Resolução dos artefatos de fallback no Nexus (decide Plano B × C)

Rodar `mvn -s settings.xml dependency:get -Dartifact=<GAV>` para cada um. **Marque o que resolve.**

| # | Artefato (fallback de) | GAV (versão de exemplo) | Resolve? |
|---|---|---|---|
| 3.1 | Redis client | `org.springframework.boot:spring-boot-starter-data-redis:3.3.x` | ☐ |
| 3.2 | Redis embarcado | `com.github.codemonstur:embedded-redis:1.4.3` | ☐ |
| 3.3 | Cache local (alt. Redis) | `com.github.ben-manes.caffeine:caffeine:3.1.x` | ☐ |
| 3.4 | AMQP / RabbitMQ | `org.springframework.boot:spring-boot-starter-amqp:3.3.x` | ☐ |
| 3.5 | Broker AMQP embarcável | `org.apache.qpid:qpid-broker:9.x` | ☐ |
| 3.5b | ⚠️ Qpid arrasta `com.sleepycat:je` (licença restrita, costuma faltar) | confirmar que resolve **OU** que a exclusão no pom basta (store em memória) | ☐ |
| 3.6 | Kafka | `org.springframework.kafka:spring-kafka:3.x` | ☐ |
| 3.7 | Kafka broker in-JVM | `org.springframework.kafka:spring-kafka-test:3.x` (traz `EmbeddedKafkaBroker`) | ☐ |
| 3.8 | Contract test | `au.com.dius.pact.consumer:junit5:4.6.x` e `...pact.provider:junit5:4.6.x` | ☐ |
| 3.9 | Testcontainers (só se Docker) | `org.testcontainers:junit-jupiter:1.20.x` | ☐ |

> **Se 3.5/3.7 NÃO resolverem:** o hands-on de filas/tópicos cai no **Plano C** (event bus in-process + design), e abrimos chamado p/ publicar o artefato no Nexus.

## 4. Rede e processos locais

| # | Verificação | Como | Importa para |
|---|---|---|---|
| 4.1 | Abrir porta em `localhost` | subir app Spring na 8080 e acessar | qualquer broker/cache local |
| 4.2 | Broker local escuta porta | Qpid/Kafka embarcado sobe em porta alta | Plano B |
| 4.3 | Sites externos bloqueados? | tentar start.spring.io, Maven Central, GitHub | confirma "tudo via Nexus" + material offline |

## 5. Colaboração e didática no Teams (bloqueante p/ projeto em grupo)

| # | Verificação | Importa para |
|---|---|---|
| 5.1 | **Breakout rooms** liberados | tempo de projeto em grupo na 2ª metade da aula |
| 5.2 | Aluno compartilha tela / recebe controle | mob/pair programming |
| 5.3 | Onde os grupos versionam código (GitLab/Bitbucket interno?) | entrega avaliável do projeto final |
| 5.4 | IDE na máquina Caixa (IntelliJ/VS Code/Eclipse) + plugins | live coding |
| 5.5 | Gravação da aula permitida | resiliência (rede caindo) e revisão assíncrona |

---

## 6. Veredito por tema (preencher no relatório)

| Tema | Plano vigente (A/B/C) | Observação |
|---|---|---|
| Redis / cache | | |
| RabbitMQ / filas | | |
| Kafka / tópicos | | |
| Contract test (PACT) | | |
| Projeto em grupo (Teams) | | |

> **Decisão de fallback padrão (se a Semana 0 não acontecer a tempo):** assumir **Plano B** (pura-JVM) em tudo — roda em qualquer máquina com Java 21 + Maven + Nexus, sem depender de Docker. É o piso seguro.
