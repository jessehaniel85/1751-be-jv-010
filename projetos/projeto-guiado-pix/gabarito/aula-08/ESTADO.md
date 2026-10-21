# Estado após a Aula 8 — Contract testing (PACT)

**Foco:** travar a compatibilidade entre serviços no CI, sem subir tudo junto.

## O que funciona / o que mudou
- **Contrato consulta ↔ gravador** (GET `/comprovantes/{id}`):
  - **Consumidor** (`comprovante-consulta`): `GravadorContractTest` declara as duas interações (existe → 200,
    não existe → 404) e **gera o pact**, validando o `GravadorClient` contra um mock.
  - **Provedor** (`comprovante-gravador`): `GravadorVerificacaoTest` **verifica** o pact (`@PactFolder("pacts")`)
    contra o serviço real, com dois `@State` (semeia e garante a ausência).
- **Message pacts** (sem broker no teste):
  - evento `comprovante-gravado`: consumidor `EventoGravadoContractTest` (notificação), provedor
    `EventoGravadoVerificacaoTest` (roda o `GravadorService` e serializa como a produção);
  - comando da fila: consumidor `ComandoGravacaoContractTest` (gravador), provedor
    `ComandoGravacaoVerificacaoTest` (emissor). É o teste que pega a divergência snake_case × camelCase.
- O pact é **Docker-free** (arquivo em disco) — funciona igual nos Planos A/B/C.

## Por que consulta↔gravador (e não emissor↔gravador)
Desde a Aula 4 a integração emissor→gravador é por **fila** (contrato de mensagem), não HTTP.
O par **HTTP** que permanece é consulta→gravador; a fila e o tópico têm *message pacts*.

## Rodando
- `mvn test` roda os 8 testes: consulta 2, notificação 1, emissor 1 e gravador 4.
- Cada consumidor grava o pact em `target/pacts`; copie-o para `src/test/resources/pacts` do provedor
  (num time real, o Pact Broker faz esse transporte). Se o `target/pacts` acumular interações antigas, apague a pasta.
- A verificação HTTP do gravador sobe o contexto Spring e leva perto de 1 minuto, com ou sem brokers.

## Fim do gabarito
A Aula 9 é a banca de defesa — sem incremento de código.

## Correções e perfil `demo` (21/10/2026, Aula 7)
- **Fila emissor → gravador:** o emissor publica o comando em `snake_case`; o gravador lia em
  `camelCase` e todo comprovante caía na DLQ (id nulo). O conversor da fila do gravador agora lê
  `snake_case`; o HTTP interno segue em `camelCase` (contrato PACT com a consulta).
- **Circuit breaker:** faltava `spring-boot-starter-aop` na notificação; sem ele o `@CircuitBreaker` não agia.
- **PACT consumidor:** `GravadorContractTest` declara `pactVersion = V3` (V4 com `RequestResponsePact` falha no Pact 4.6).
- **Perfil `demo`** (só a notificação): `POST /demo/gateway?falhar=&propagar=&lentidao=` controla o gateway simulado
  sem editar código; `POST /demo/gateway/rajada?n=` dispara chamadas simultâneas (bulkhead de 2 threads e fila de 2);
  `GET /demo/reatores` mostra BI, compliance, reenvios e falhas permanentes. O actuator expõe `circuitbreakers`,
  `circuitbreakerevents`, `bulkheads` e `ratelimiters`. `propagar=false` agenda reenvio (sem retry/DLT);
  `propagar=true` leva à DLT.

## Alinhamento ao material (21/10/2026)
O gabarito das aulas 1 a 8 passou a seguir o código do material do aluno (nomes, modelo de domínio, topologia,
resiliência e contratos). Onde o material se contradizia, não compilava ou estava errado, o material é que foi
ajustado ao código.
- Demo sobre clusters RabbitMQ e Kafka: ver `../demo/` e o roteiro `plano-de-aulas/aula-07-complemento-demo-final.md` (na raiz do repositório).
