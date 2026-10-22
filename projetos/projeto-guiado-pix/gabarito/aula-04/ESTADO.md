# Estado após a Aula 4 — Comunicação assíncrona (producer/consumer)

**Foco:** desacoplar a emissão da gravação.

## O que funciona / o que mudou
- **emissor**: `EmissaoController` + `EmissorDeComprovante.aceitarEPublicar`: valida, responde **202** e
  **publica** `GravarComprovanteCommand` na exchange `comprovantes.exchange` (routing key
  `comprovante.gravar`), ligada à fila `gravacao.q` (não espera mais a gravação). Saiu o cliente REST síncrono da Aula 2.
- **gravador**: **@RabbitListener** consome a fila e grava (idempotente sob redelivery).
- Mensagens em **JSON** (Jackson + suporte a LocalDateTime).
- A consulta (Aula 3) continua igual.

## Garantias (ainda parciais)
Entrega é **at-least-once** + **idempotência** no consumidor. Tratamento de falha
(retry/DLQ) e topologia formal de exchange entram na **Aula 5**.

## Runtime do broker
- **Plano B (esta turma):** broker **Qpid embarcado/local** — pendente confirmar no Nexus (ver `ambiente/relatorio-semana-0.md`). Se faltar o artefato, cai no `plano-c-conceitual`.
- **Plano A (referência de produção, não usado nesta turma):** RabbitMQ via Docker (`guest/guest`, 5672).
  (O código compila sem o broker; para executar o fluxo ponta a ponta é preciso um broker no ar.)

## Próxima aula
Topologia de filas com exchange/binding, **DLQ** e **retry**.
