# Estado após a Aula 6 — Tópicos / eventos com Kafka

**Foco:** publicar um FATO para vários consumidores sem o produtor conhecê-los.

## O que funciona / o que mudou
- **gravador**: após persistir, publica `ComprovanteGravadoEvent(comprovanteId, chavePixDestino, valor, gravadoEm)`
  no tópico `comprovante-gravado` (chave = id → ordenação por comprovante; 3 partições; datas em ISO-8601).
- **novo módulo `comprovante-notificacao`** (porta 8084), com um consumer group por reação:
  `notificacao` (avisa o cliente), `antifraude`, `bi` (volumetria em memória) e `compliance`
  (nasce desligado, `pix.compliance.ativo`; ligado, lê o tópico desde o início).
- Fila (RabbitMQ) continua entregando o **trabalho** de gravar; o tópico (Kafka) publica o **fato**.

## Vários consumidores
Antifraude, BI e compliance são consumer groups lendo o MESMO tópico, com offset próprio —
cada um é um `@KafkaListener(groupId="...")` novo, sem tocar no gravador.

## Runtime do broker
- **Plano A:** Kafka/Redpanda via Docker.
- **Plano B:** `EmbeddedKafkaBroker` (spring-kafka-test) / Kafka local. Compila sem broker;
  o fluxo ponta a ponta exige um broker no ar.

## Próxima aula
Resiliência no consumo de eventos: `@RetryableTopic` + circuit breaker.
