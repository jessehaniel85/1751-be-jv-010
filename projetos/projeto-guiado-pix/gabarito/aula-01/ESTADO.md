# Estado após a Aula 1 — DDD e bases segregadas

**Foco:** modelar os domínios e separar a solução em microsserviços com bases próprias.

## O que funciona
- **3 serviços** com bases H2 **segregadas**: `comprovante-emissor` (8081), `comprovante-gravador` (8082), `comprovante-consulta` (8083).
- **emissor**: `POST /comprovantes` valida as informações obrigatórias (agência=4, conta=5, dígito=1, valor>0, chave/tipo) → **202** com `identificador_comprovante`. Campo inválido → **400**.
- **gravador**: agregado `Comprovante` (raiz) com os value objects `ChavePix` e `Dinheiro` e o `StatusComprovante` (só avança de `ACEITO` para `GRAVADO`, via `marcarGravado()`) + repositório + `GravadorService.gravar()` **persistem de verdade** — mas ainda **ninguém invoca** (a orquestração vem na Aula 2).
- **consulta**: `GET /comprovantes/{id}` ainda responde **404** (o caminho de dados/cache entra na Aula 3).

## Decisões (ADR)
- `docs/adr/ADR-001-bases-segregadas.md` — base por serviço, integração só por contrato.

## Como rodar
```bash
mvn -s ../../../../ambiente/settings.xml -Pplano-b-jvm clean compile
mvn -s ../../../../ambiente/settings.xml -Pplano-b-jvm -pl comprovante-emissor spring-boot:run
```

## Próxima aula
SAGA: a emissão passa a orquestrar a gravação (síncrona) com idempotência e compensação.
