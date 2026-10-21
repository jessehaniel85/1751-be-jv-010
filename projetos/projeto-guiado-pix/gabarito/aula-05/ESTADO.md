# Estado após a Aula 5 — Filas com RabbitMQ + idempotência e DLQ

**Foco:** rotear, confirmar e isolar mensagens que não dá para processar.

## O que funciona / o que mudou
- **Topologia formal:** `comprovantes.exchange` (direct) → `gravacao.q` (routing key `comprovante.gravar`).
- **Retry** com backoff para falha **transitória**: `RetryOperationsInterceptor` com 4 tentativas (1 s, 2 s, 4 s),
  no `rabbitListenerContainerFactory` (prefetch 10).
- **Falha permanente** (comando malformado): `AmqpRejectAndDontRequeueException`, classificada como não-retentável,
  vai **direto** para a DLQ.
- **DLQ**: a mensagem vai para `gravacao.dlq` (via `gravacao.dlx`, direct, routing key `comprovante.morto`),
  **preservada para inspeção**: a DLQ não tem consumidor; veja a mensagem e os headers `x-death` no painel.
- Corpo ilegível (`MessageConversionException`) também é falha permanente: direto para a DLQ.
- **Idempotência** mantida no `gravar()` (redelivery seguro).

## Como provar a DLQ
Force o `gravar()` a lançar exceção para um id específico (mensagem "envenenada") e observe:
4 tentativas → cai na `gravacao.dlq` (veja no painel do RabbitMQ). Mensagem boa segue normal.

## Runtime do broker
Igual à Aula 4 (Plano A: RabbitMQ Docker; Plano B: Qpid embarcado/local).

## Próxima aula
Eventos: o gravador publica "comprovante gravado" num **tópico Kafka** para vários consumidores.
