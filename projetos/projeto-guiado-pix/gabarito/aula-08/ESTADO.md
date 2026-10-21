# Estado após a Aula 8 — Contract testing (PACT)

**Foco:** travar a compatibilidade entre serviços no CI, sem subir tudo junto.

## O que funciona / o que mudou
- **Contrato consulta ↔ gravador** (GET `/interno/comprovantes/{id}`):
  - **Consumidor** (`comprovante-consulta`): `GravadorContractTest` declara a expectativa e
    **gera o pact**, validando o `GravadorReadClient` contra um mock.
  - **Provedor** (`comprovante-gravador`): `GravadorProviderTest` **verifica** o pact
    (`src/test/resources/pacts/...json`) contra o serviço real, com `@State` semeando o dado.
- O pact é **Docker-free** (arquivo em disco) — funciona igual nos Planos A/B/C.

## Por que consulta↔gravador (e não emissor↔gravador)
Desde a Aula 4 a integração emissor→gravador é por **fila** (contrato de mensagem), não HTTP.
O par **HTTP** que permanece é consulta→gravador — daí o contract test aqui. (Pact também
suporta *message pacts* para o fluxo de fila — fica como extensão.)

## Rodando
- Consumidor: `mvn -pl comprovante-consulta test` gera o pact em `target/pacts`.
- Provedor: `mvn -pl comprovante-gravador test` verifica o pact.
  O provedor sobe o contexto Spring; em ambiente sem Kafka/RabbitMQ, ative um profile de teste
  que desabilite a mensageria (ou aponte para EmbeddedKafka/Qpid) para o app iniciar.

## Fim do gabarito
A Aula 9 é a banca de defesa — sem incremento de código.

## Correções e perfil `demo` (21/10/2026, Aula 7)
- **Fila emissor → gravador:** o emissor publica o comando em `snake_case`; o gravador lia em
  `camelCase` e todo comprovante caía na DLQ (id nulo). O conversor da fila do gravador agora lê
  `snake_case`; o HTTP interno segue em `camelCase` (contrato PACT com a consulta).
- **Circuit breaker:** faltava `spring-boot-starter-aop` na notificação; sem ele o `@CircuitBreaker` não agia.
- **PACT consumidor:** `GravadorContractTest` declara `pactVersion = V3` (o Pact 4.6 assume V4 por padrão e o teste falhava).
- **Perfil `demo`** (só a notificação): `POST /demo/gateway?falhar=&propagar=` liga e desliga a falha do
  gateway sem editar código; actuator expõe `circuitbreakers` e `circuitbreakerevents`.
  `propagar=false` mostra o fallback engolindo a falha (sem retry/DLT); `propagar=true` leva à DLT.
- Demo sobre clusters RabbitMQ e Kafka: ver `../demo/` e o roteiro `plano-de-aulas/aula-07-complemento-demo-final.md` (na raiz do repositório).
