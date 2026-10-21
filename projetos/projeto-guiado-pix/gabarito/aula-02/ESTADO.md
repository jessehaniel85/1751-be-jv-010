# Estado após a Aula 2 — Consistência distribuída e SAGA

**Foco:** garantir atomicidade emissão→gravação sem transação distribuída.

## O que funciona
- **SAGA orquestrada (síncrona):** o `EmissaoSagaOrchestrator` registra a emissão (`EmissaoService.registrar`),
  chama o **gravador via REST** (`GravadorClient`, `POST /comprovantes`, timeout de 2 s) e, se a gravação falhar
  (`GravacaoException`), executa a **compensação** (`EmissaoService.invalidar`: status `INVALIDADO` com o motivo)
  e devolve **502** (`SagaAbortada`).
- **Idempotência** no gravador: reprocessar o mesmo `id` não duplica (`existsById`, em `@Transactional`).
- Sucesso → **202** com o id.

## Por que síncrono aqui
A orquestração é o conceito da aula; o **desacoplamento por fila** vem na Aula 4 — então
ainda há acoplamento temporal (emissor e gravador precisam estar de pé juntos).

## Como rodar (2 serviços)
```bash
mvn -Pplano-b-jvm -pl comprovante-gravador spring-boot:run   # 8082
mvn -Pplano-b-jvm -pl comprovante-emissor  spring-boot:run   # 8081
```

## Próxima aula
Cache na consulta (Redis/Caffeine) com fallback no gravador e 3 retentativas.
