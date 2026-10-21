# Estado após a Aula 7 — Resiliência: retry, circuit breaker e @RetryableTopic

**Foco:** reentregar sem inundar e falhar rápido quando a dependência está fora.

## O que funciona / o que mudou
- **notificacao**: o consumo usa **@RetryableTopic** (4 tentativas, backoff exponencial com jitter, `FAIL_ON_ERROR`) —
  o spring-kafka cria `comprovante-gravado-retry-0/1/2` e a **DLT**; o `@DltHandler` chama o `AlertaService`.
- `NotificacaoService.avisar` é **idempotente** (`NotificacaoRepository`, chave = id do comprovante).
- O `GatewayCliente` protege a chamada ao gateway externo com **bulkhead** (THREADPOOL, por isso devolve
  `CompletableFuture`), **circuit breaker** (`gatewayNotificacao`: janela 10, 50%, 30 s aberto) e
  **rate limiter** (100 por segundo); o **fallback** agenda reenvio (`ReenvioRepository`) **e devolve a falha**,
  que segue para o retry e a DLT. O `ReenvioService` drena o reenvio, um por vez, quando o circuito volta a *closed*.
- Para exercitar: descomente o `throw` em `GatewayHttp.post` e observe o reenvio agendado, o retry → DLT e a abertura do circuito.

## Runtime
Requer Kafka no ar (Plano A Docker / Plano B EmbeddedKafka ou local). Compila sem broker.

## Próxima aula
Contract testing (PACT) entre emissor (consumer) e gravador (provider).
