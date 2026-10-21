# Estado após a Aula 3 — Cache com Redis (Caffeine no Plano B)

**Foco:** aliviar leituras repetidas sem servir dado inconsistente.

## O que funciona
- **consulta**: `ConsultaComprovante` busca **primeiro no cache** (`ComprovanteCache`, cache-aside);
  no miss, lê o **gravador** (`GravadorClient`, `GET /comprovantes/{id}`, timeout de 2 s) com até
  **3 retentativas** e backoff de 100, 200 e 400 ms (`consulta.backoff-inicial`), **popula o cache**
  e retorna o `ComprovanteDTO`; ausência real → **404** (`ComprovanteNaoEncontrado`, nada no cache).
- **gravador**: novo endpoint de leitura com a projeção `ComprovanteView` (`id`, `valor`, `chavePix`,
  `dataHora`, `status`, …), em camelCase.
- Cache configurável (TTL 5min, Caffeine no Plano B; trocar por Redis no Plano A sem mexer no serviço).

## Como rodar
```bash
mvn -Pplano-b-jvm -pl comprovante-gravador spring-boot:run   # 8082
mvn -Pplano-b-jvm -pl comprovante-consulta spring-boot:run   # 8083
```

## Próxima aula
Desacoplar a gravação: a emissão publica numa fila e o gravador consome (async).
