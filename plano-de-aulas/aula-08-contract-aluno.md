# Material do Aluno — Aula 8: Contract testing com PACT (e evals como contrato)

> **Tempo de leitura:** ~12 min. Esta é a aula que fecha o ciclo técnico do módulo: você desenhou fronteiras (Aula 1), separou bases, montou mensageria, cache e resiliência — e agora precisa de uma forma de **provar que dois serviços que conversam continuam compatíveis** sem subir o ambiente inteiro toda vez. Contract testing é o mecanismo que transforma "acho que o gravador ainda devolve o JSON certo" em "o CI quebra antes do deploy se ele não devolver". Leia com o seu projeto em mente: o par de serviços mais crítico do seu sistema é o que você vai blindar aqui.

---

## 1. O problema gerador

Dois times diferentes mantêm o `comprovante-consulta` e o `comprovante-gravador`. O consulta chama o gravador por REST (`GET /comprovantes/{id}`) para buscar a fonte da verdade quando o cache dá *miss*. Um dia, alguém do time do gravador renomeia o campo `valor` para `valorTransacao` no JSON de resposta, "porque ficou mais claro". O build do gravador passa verde — todos os testes dele continuam passando, afinal o serviço dele funciona. O merge entra. E só **em produção**, quando o consulta tenta desserializar a resposta e recebe `null` no valor, é que a quebra aparece — no serviço do **outro time**, que não mudou uma linha de código.

Esse é o pesadelo do distribuído: a quebra não está dentro de um serviço, está **no espaço entre eles**. Nenhum teste do gravador a pega (ele não sabe quem consome); nenhum teste do consulta a pega (ele usa um mock escrito à mão, com o campo antigo). A pergunta da aula é direta: **como travar essa quebra no CI, antes do deploy, sem subir os cinco serviços juntos?**

---

## 2. A pirâmide de testes em distribuído (e por que E2E não escala)

A pirâmide de testes clássica diz: muitos testes **unitários** na base (rápidos, isolados, baratos), alguns de **integração** no meio, e **pouquíssimos** end-to-end (E2E) no topo. Em um monólito isso já é verdade. Em microsserviços, o topo da pirâmide vira uma armadilha cara.

| Camada | O que testa | Custo | Velocidade | Estabilidade |
|---|---|---|---|---|
| **Unitário** | regra de domínio isolada (um agregado, um VO) | baixíssimo | ms | altíssima |
| **Integração (componente)** | um serviço com suas dependências reais (banco, broker) | médio | s | alta |
| **Contract test** | a **compatibilidade** entre consumidor e provedor | baixo | s | alta |
| **E2E** | o fluxo inteiro com os N serviços de pé | altíssimo | min | baixa |

Por que **E2E não escala** num sistema distribuído:

- **Caro de montar e manter.** Para testar um fluxo de ponta a ponta você precisa subir os cinco serviços, os bancos de cada um, o broker, a fila, o tópico — e manter tudo isso versionado e coerente. O custo de infraestrutura e de manutenção cresce de forma combinatória com o número de serviços.
- **Lento.** Cada E2E sobe ambiente, espera *health checks*, semeia dados, executa e derruba tudo. Minutos por teste. Um pipeline cheio de E2E faz o feedback do CI passar de segundos para dezenas de minutos — e o time para de rodar.
- **Frágil (*flaky*).** Quanto mais peças móveis, mais o teste falha por motivos que **não são bug do código**: timeout de rede, ordem de subida, dado residual, *race condition*. Testes que falham aleatoriamente são piores que ausência de teste, porque o time aprende a ignorá-los — e aí o E2E que pegaria um bug real também é ignorado.

A consequência: E2E é ótimo para **um punhado** de jornadas críticas ("emitir e depois consultar um comprovante"), mas é o instrumento errado para responder "o gravador ainda honra o que o consulta espera?". Essa pergunta é de **integração entre dois serviços** — e cobri-la com E2E é matar mosca com canhão.

### A lacuna que o contract testing preenche

Repare no buraco: o mock do gravador usado pelo teste do consulta pode estar desatualizado — ele afirma um contrato que o gravador real **não cumpre mais**. E o teste do gravador **não sabe** que existe um consulta dependente do campo `valor`. Há um acordo implícito que **nenhum dos dois lados testa**. O contract testing fecha exatamente essa lacuna: torna o acordo **explícito e executável** e o roda **dos dois lados** — garantindo que o mock do consumidor e a resposta real do provedor descrevem o **mesmo** contrato.

---

## 3. Consumer-driven contracts

Há duas formas de pensar um contrato entre serviços. Na abordagem **provider-driven**, o provedor publica "esta é a minha API, consumam como puderem". O problema: o provedor não sabe quais campos cada consumidor realmente usa, então qualquer mudança é arriscada (pode quebrar alguém invisível) e ao mesmo tempo o provedor carrega campos que ninguém consome.

Na abordagem **consumer-driven contracts (CDC)** — a que o PACT implementa — a direção se inverte: **cada consumidor declara exatamente o que precisa** do provedor (quais campos, formatos, status HTTP), e o conjunto desses contratos vira a especificação que o provedor **deve** honrar. Isso traz três ganhos diretos:

- **Mudança segura.** O provedor sabe, de forma executável, o que cada consumidor depende. Ele pode mudar livremente tudo que **nenhum** contrato exige; e descobre na hora se mudou algo que alguém usa.
- **Contrato mínimo.** O consumidor pede só o que usa. Se o consulta só precisa de `id`, `valor` e `dataHora`, o contrato dele não amarra os outros 20 campos da resposta do gravador.
- **Compatibilidade verificável.** Como o contrato é gerado pelo consumidor e verificado pelo provedor, os dois lados nunca podem divergir silenciosamente.

CDC é a versão executável do padrão **Published Language / Open Host Service** do context mapping (Aula 1): o contrato deixa de ser um documento e passa a ser um artefato que roda no pipeline.

---

## 4. A mecânica do PACT

PACT é a ferramenta de referência para CDC. O nome do artefato central é o **pact file**: um JSON que descreve cada interação esperada (requisição → resposta). O ciclo tem dois lados, sempre nesta ordem:

1. **Lado consumidor (gera o pact).** Você escreve um teste que declara: "quando eu chamar `GET /comprovantes/{id}` com este id, espero status `200` e um corpo com estes campos". O PACT sobe um **mock do provedor** que responde exatamente o que você declarou, roda o **seu código cliente real** contra esse mock (validando que ele de fato monta a requisição certa e consegue ler a resposta), e — se passar — **grava o pact file** em disco. O pact file é a expectativa, agora num arquivo versionável.
2. **Lado provedor (verifica o pact).** O PACT pega o pact file e **replay** cada interação contra o **provedor real** rodando: faz a requisição declarada e confere se a resposta real bate com a esperada. Se o gravador renomeou `valor` para `valorTransacao`, a verificação **falha aqui**, no CI do gravador — exatamente onde o time que causou a quebra vai vê-la.

O ponto central, e o que mais confunde quem está começando: **o consumidor declara e GERA; o provedor VERIFICA.** O contrato nasce de quem consome (porque é quem sabe o que precisa) e é cobrado de quem provê.

```
  ┌─────────────────────┐                       ┌─────────────────────┐
  │ comprovante-consulta│                       │ comprovante-gravador│
  │     (consumer)      │                       │      (provider)     │
  ├─────────────────────┤                       ├─────────────────────┤
  │ teste declara a      │   gera o pact file    │ verificação roda o   │
  │ expectativa contra   │ ───── (JSON) ──────►  │ pact contra o serviço│
  │ um MOCK do provedor  │                       │ REAL e confere       │
  └─────────────────────┘                       └─────────────────────┘
         GERA                                            VERIFICA
```

---

## 5. Exemplo trabalhado: contrato do `GET /comprovantes/{id}`

Vamos blindar a integração real do projeto: o `comprovante-consulta` (consumer) chama o `comprovante-gravador` (provider) para buscar um comprovante por id quando o cache dá *miss*. Eis o cliente do lado do consulta:

```java
// comprovante-consulta — o cliente REST que conversa com o gravador.
@Component
public class GravadorClient {
    private final RestClient http;

    public GravadorClient(RestClient.Builder b, @Value("${gravador.url}") String url) {
        this.http = b.baseUrl(url).build();
    }

    public Optional<ComprovanteDTO> buscarPorId(UUID id) {
        return Optional.ofNullable(
            http.get().uri("/comprovantes/{id}", id)
                .retrieve()
                .body(ComprovanteDTO.class));
    }
}
```

### 5.1. Lado consumidor — declara e gera o pact

O teste do consulta declara a interação que ele precisa e roda o `GravadorClient` real contra o mock que o PACT levanta:

```java
// comprovante-consulta/src/test/java — gera o pact file.
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "comprovante-gravador", pactVersion = PactSpecVersion.V3)
class GravadorContractTest {

    @Pact(consumer = "comprovante-consulta")
    RequestResponsePact comprovanteExiste(PactDslWithProvider builder) {
        return builder
            .given("comprovante 7f3a... existe e está gravado")   // provider state
            .uponReceiving("busca de comprovante por id existente")
                .path("/comprovantes/7f3a0e4c-1d2b-4c8a-9f01-2a3b4c5d6e7f")
                .method("GET")
            .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                    .uuid("id", "7f3a0e4c-1d2b-4c8a-9f01-2a3b4c5d6e7f")
                    .numberType("valor", 150.00)          // o consulta DEPENDE de "valor"
                    .stringType("chavePix", "joao@pix.com")
                    .datetime("dataHora", "yyyy-MM-dd'T'HH:mm:ss")
                    .stringMatcher("status", "GRAVADO|ACEITO", "GRAVADO"))
            .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "comprovanteExiste")
    void leRespostaDoGravador(MockServer mock) {
        // o builder de produção (GravadorClientConfig: timeout e camelCase do gravador),
        // com o ObjectMapper snake_case desta API — assim o teste pega a divergência de nomes
        var client = new GravadorClient(new GravadorClientConfig()
                .restClientBuilder(Duration.ofSeconds(2), Jackson2ObjectMapperBuilder.json()
                        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()),
                mock.getUrl());

        var dto = client.buscarPorId(
            UUID.fromString("7f3a0e4c-1d2b-4c8a-9f01-2a3b4c5d6e7f"));

        assertThat(dto).isPresent();
        assertThat(dto.get().valor()).isEqualByComparingTo("150.00");  // valida o que importa
        assertThat(dto.get().chavePix()).isEqualTo("joao@pix.com");    // nome composto: pega snake × camel
    }
}
```

Repare em dois pontos de design. Primeiro, usamos **matchers** (`numberType`, `stringType`, `stringMatcher`) e não valores literais: o contrato afirma "existe um campo `valor` que é número", não "o valor é exatamente 150.00". Contrato é sobre **forma e tipo**, não sobre o dado específico — isso evita acoplar o teste a um valor que muda. Segundo, o teste pede **só os campos que o consulta usa**. Se o gravador devolve mais 15 campos, o contrato não se importa — é o CDC em ação.

### 5.2. Lado provedor — verifica o pact

No `comprovante-gravador`, a verificação sobe o serviço real e faz o replay do pact:

```java
// comprovante-gravador/src/test/java — verifica o pact contra o serviço real.
@Provider("comprovante-gravador")
@Consumer("comprovante-consulta")
@PactFolder("pacts")                       // lê os pact files gerados pelo consumer
@SpringBootTest(webEnvironment = RANDOM_PORT)
class GravadorVerificacaoTest {

    private static final UUID EXISTENTE = UUID.fromString("7f3a0e4c-1d2b-4c8a-9f01-2a3b4c5d6e7f");

    @LocalServerPort int porta;
    @Autowired ComprovanteRepository repo;

    @BeforeEach
    void target(PactVerificationContext ctx) {
        ctx.setTarget(new HttpTestTarget("localhost", porta));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verificaContrato(PactVerificationContext ctx) {
        ctx.verifyInteraction();           // replay de cada interação do pact
    }

    // provider state: semeia o dado que o pact assume existir.
    @State("comprovante 7f3a... existe e está gravado")
    void semeiaComprovante() {
        if (repo.existsById(EXISTENTE)) {
            return;
        }
        Comprovante comprovante = Comprovante.aPartirDe(new GravarComprovanteCommand(EXISTENTE,
                new ComprovanteRequest("João", TipoDocumento.CPF, "50329291076", "2022", "00276", "0",
                        new BigDecimal("150.00"), TipoChave.EMAIL, "joao@pix.com",
                        "Maria", "E00000000202610211500", LocalDateTime.parse("2026-10-21T15:00:00"))));
        comprovante.marcarGravado();
        repo.save(comprovante);
    }
}
```

O `@Consumer("comprovante-consulta")` filtra os pacts deste consumidor: a pasta `pacts` também guarda os message pacts (seção 8), que esta verificação HTTP não deve tentar reproduzir.

Se alguém no gravador renomear `valor` para `valorTransacao`, `verificaContrato` falha com uma mensagem precisa ("esperado campo `valor`, não encontrado") — no CI do gravador, no commit que causou o problema. A quebra de produção virou um build vermelho.

---

## 6. Provider states — semeando o dado

O pact da seção 5 assume que **existe** um comprovante com aquele id. Mas o gravador, num teste limpo, tem o banco vazio. Como a verificação encontra esse dado? Pelo **provider state**: o `given("comprovante ... existe e está gravado")` do consumidor é uma **etiqueta** que, no provedor, casa com um método `@State` de mesmo nome. Esse método roda **antes** da interação e **semeia o pré-requisito** — no exemplo, ele salva o comprovante no repositório.

Provider state é o que mantém o contrato **independente do estado global do banco**: cada interação declara seu próprio pré-requisito ("dado que X existe", "dado que X **não** existe") e o provedor sabe montar exatamente esse cenário. Você terá um `@State` para o caso de comprovante existente (resposta 200) e outro para o inexistente (resposta 404), e cada um semeia — ou limpa — só o que aquela interação precisa.

---

## 7. Pact Broker e `can-i-deploy`

Pact files em disco resolvem o caso de um repositório só. Em escala, com vários consumidores e provedores evoluindo em ritmos diferentes, surge a pergunta de versionamento: *"esta versão do gravador é compatível com as versões de consulta que estão em produção agora?"*. Responder isso à mão é inviável.

O **Pact Broker** é um servidor que centraliza os pact files e os associa a **versões** de cada serviço e a **ambientes** (`production`, `staging`). Cada lado publica seu resultado: o consumidor publica o pact que gerou; o provedor publica que verificou (ou não) aquele pact. Com isso o Broker mantém uma **matriz de compatibilidade**: quem é compatível com quem, em qual versão.

Sobre essa matriz roda o comando `can-i-deploy`, a peça que entra no pipeline antes de cada deploy:

```bash
# "Posso subir esta versão do gravador para produção sem quebrar ninguém?"
pact-broker can-i-deploy \
  --pacticipant comprovante-gravador \
  --version "$GIT_SHA" \
  --to-environment production
```

Se algum consumidor que está em produção depende de algo que esta versão do gravador **não** honra mais, o comando retorna erro e o **deploy é bloqueado**. É a tradução literal da frase-síntese da aula: contrato bom é o que **falha no seu CI**, não na produção do outro time.

> No nosso ambiente (sem Docker), o Pact-JVM roda com os **pact files em disco** e a verificação do provedor **em processo** — Docker-free, e por isso este tema é o mais tranquilo do módulo. O **Pact Broker** (com `can-i-deploy` no pipeline) é o passo de produção que você verá em serviços modernos — você precisa **saber explicá-lo na banca** mesmo sem subi-lo aqui.

---

## 8. Message pacts — contrato de mensagem, não só de HTTP

Nem toda integração do seu sistema é REST. O `comprovante-emissor` fala com o `comprovante-gravador` por **fila**, e o gravador publica `ComprovanteGravado` num **tópico** que a notificação consome. Esses acoplamentos também têm contrato — e quebram do mesmo jeito quando alguém muda o formato da mensagem.

O PACT cobre isso com **message pacts**: em vez de requisição/resposta HTTP, o contrato descreve a **mensagem** que o produtor emite e o consumidor espera. A mecânica espelha o caso HTTP: o **consumidor da mensagem** (a notificação) declara "espero uma mensagem com estes campos" e gera o pact; o **produtor** (o gravador) é verificado — o PACT chama o código que produz a mensagem e confere se o payload bate com o contrato, **sem precisar de broker real no teste**.

```java
// notificacao (consumer da mensagem): declara o contrato do evento ComprovanteGravado.
/** Instante em ISO-8601 (UTC), com ou sem fração de segundo: é como o gravador o publica. */
private static final String ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

@Pact(consumer = "comprovante-notificacao")
MessagePact eventoGravado(MessagePactBuilder builder) {
    return builder
        .expectsToReceive("evento de comprovante gravado")
        .withContent(new PactDslJsonBody()
            .uuid("comprovanteId")
            .stringType("chavePixDestino")
            .numberType("valor")              // antifraude e BI, no mesmo módulo, usam o valor
            .stringMatcher("gravadoEm", ISO_INSTANT, "2026-10-21T18:00:00Z"))
        .toPact();
}
```

O `gravadoEm` usa `stringMatcher` com uma expressão ISO, e não `datetime`: o gravador publica o instante em ISO-8601 UTC, com fração de segundo variável, que o `datetime` com formato fixo não aceita.

Assim a fronteira assíncrona — não só a REST — fica protegida no CI. Um message pact é o contrato do seu **tópico/fila**, e ele é tão verificável quanto o de um endpoint.

O gabarito também tem o message pact do comando da fila (gravador ← emissor), `ComandoGravacaoContractTest`, que falha no build se o emissor e o gravador divergirem em snake_case/camelCase.

---

## 9. Evolução e compatibilidade de schema

Contratos existem para poder **mudar com segurança**. A regra que governa a evolução é a distinção entre mudança **compatível** e **incompatível**:

- **Compatível (não quebra consumidores):** adicionar um campo **novo opcional** na resposta; aceitar um campo novo opcional na requisição; relaxar uma validação. Consumidores antigos ignoram o que não conhecem.
- **Incompatível (quebra):** **remover** ou **renomear** um campo que algum consumidor usa; mudar o **tipo** de um campo; tornar **obrigatório** um campo antes opcional; mudar a semântica de um valor.

A estratégia segura para uma mudança incompatível é **expand and contract** (também chamada de *parallel change*): primeiro **adicione** o novo formato mantendo o antigo (fase *expand*), migre cada consumidor para o novo, confirme via `can-i-deploy` que ninguém usa mais o antigo, e só então **remova** o velho (fase *contract*). O contract test é o que torna esse processo seguro: ele diz, a qualquer momento, **quem ainda depende do quê**.

---

## 10. Arquitetura amigável a testes

Tudo acima só é barato se o código **colabora**. Contract testing — e teste em geral — é uma **consequência de design**, não algo que se adiciona no fim:

- **Injeção de dependência.** O `GravadorClient` recebe a URL base por construtor; por isso o teste consegue apontá-lo para o `mock.getUrl()` sem nenhuma gambiarra. Dependência *hardcoded* (um `new RestClient()` com URL fixa dentro do método) torna o serviço impossível de redirecionar para o mock.
- **Fronteiras explícitas.** A conversa com o gravador está **isolada** num cliente dedicado (`GravadorClient`), não espalhada por dez serviços. A fronteira é o lugar exato onde o contrato se aplica — e onde o teste se prende.
- **Determinismo onde importa.** Tudo que é não-determinístico (relógio, UUID, ordem) precisa ser **injetável** (`Clock`, gerador de id), senão o teste oscila. O provider state semeia dados determinísticos justamente para que o replay do pact seja reprodutível.
- ***Side effects* isolados.** Persistência, envio de mensagem e chamadas externas atrás de portas explícitas — para que o teste substitua o que é caro e exercite o que é regra.

A heurística: se escrever o contract test deu trabalho, provavelmente o problema não é o PACT — é que a fronteira do seu serviço não estava explícita. O teste difícil é um **sintoma de design**, e arrumá-lo melhora a arquitetura, não só a suíte.

---

## 11. Ponte com o legado Caixa

Contract testing não é um conceito novo — é a versão **executável** de algo que o mundo corporativo sempre teve: o **contrato de interface**. Em sistemas legados, o acordo entre dois sistemas vivia num **documento Word, numa planilha de layout de arquivo posicional ou num PDF de "especificação de interface"** — "as posições 1 a 11 são o CPF, 12 a 26 o valor com duas casas decimais", e assim por diante. Quem integrou com mainframe via arquivo CNAB, EDI ou *copybook* conhece bem esse documento.

O problema do contrato-em-documento é que ele **não roda**. Ele envelhece, diverge do código, e ninguém revalida — até o dia em que o sistema A muda o layout, esquece de avisar o sistema B, e a quebra aparece em produção (exatamente o pesadelo da seção 1, só que com 30 anos a mais). O *batch* noturno rejeita o arquivo inteiro, ou pior, lê o campo errado silenciosamente.

Contract testing pega esse mesmo acordo e o torna **código que roda no pipeline**: o "layout de interface" vira o pact file, e a "conferência manual do layout" vira a verificação automática no CI. Para o veterano de integrações Caixa, a mensagem é direta — **é o mesmo contrato de interface de sempre, só que agora ele falha no seu build, não na produção do outro time.** Quem já desenhou layout de arquivo CNAB entende contract testing em cinco minutos; só muda o ferramental.

---

## 12. IA & agentes hoje

A mesma ideia de "contrato executável" governa como se testa um componente de IA — e é onde o tema mais cresce hoje:

- **Testar saída não-determinística.** Você não faz `assertEquals` no texto de um LLM: a mesma entrada gera saídas diferentes. O que você testa é **schema, invariantes e propriedades** — o JSON tem todos os campos obrigatórios? o valor está no intervalo permitido? a estrutura bate com o esperado? É exatamente a lógica dos **matchers** do PACT (forma e tipo, não valor literal) aplicada à saída de um modelo.
- **Evals como contrato.** Uma suíte de *evals* — casos de entrada com critérios de aceitação sobre a saída — é o **contract test de um componente de IA**: ela define o comportamento aceitável e roda no CI, exatamente como um pact define o comportamento aceitável de uma API. Mudou o prompt, o modelo ou a temperatura? A eval falha se a qualidade regrediu, antes de chegar ao usuário.
- ***Structured outputs* / JSON schema como contrato.** Forçar o modelo a responder num **JSON schema** declarado é o equivalente direto a um contrato de API: você define a forma da saída e a **valida** contra o schema como validaria a resposta de um provedor. O schema vira a fronteira verificável entre o componente de IA e o resto do sistema — o consumidor do modelo declara o que precisa, e a saída é checada contra isso. CDC, de novo, num contexto novo.

A lição transfere inteira: em um mundo de geração não-determinística (de dados ou de texto), a confiança não vem de comparar saídas exatas — vem de **declarar e verificar contratos sobre a forma e as propriedades** da saída.

---

## 13. Armadilhas comuns

- **Confiar só em E2E.** Lento, instável, e ainda dá a falsa sensação de que cobre tudo. Use E2E para um punhado de jornadas; cubra integração com contract test.
- **Contrato que ninguém roda no CI.** Um pact que não está no pipeline volta a ser documento morto — exatamente o que você queria substituir.
- **Acoplar o contrato ao detalhe interno do provedor.** O contrato é sobre a **necessidade do consumidor** (campos que ele usa), não sobre a implementação do provedor. Pedir literais em vez de matchers, ou amarrar campos que você não consome, deixa o contrato frágil.
- **Esquecer o provider state.** Sem semear o dado, a verificação falha por dado ausente — e o time culpa o PACT em vez do *setup*.

---

## 14. Para ir além

- **Documentação do Pact** (pact.io) — consumer-driven contracts, provider states, message pacts e `can-i-deploy`.
- **Martin Fowler** — verbetes *ContractTest*, *ConsumerDrivenContracts* e *TestPyramid* (martinfowler.com).
- **Pact Broker** — documentação de versionamento de contrato e da matriz de compatibilidade.
- **Guias de *LLM evals*** — avaliação de saída não-determinística por critérios, schema e propriedades (a ponte da seção 11).

> **Na próxima aula:** o código está pronto e blindado por contratos. Mas a Aula 9 não pede mais código — ela pede que você **defenda** suas decisões diante de uma banca. Por que estes bounded contexts? Por que fila aqui e tópico ali? Seu cache pode servir dado velho — e isso é aceitável? Saber construir o sistema é metade; a outra metade é **justificar cada trade-off** sob arguição. O próximo material é o guia para chegar à banca preparado.
