# Material do Aluno — Aula 4: Cache com Redis (e semantic caching)

> **Tempo de leitura:** ~12 min. A consulta de comprovante é a operação mais frequente do sistema — todo cliente quer ver o comprovante, repetidas vezes, logo após o PIX. Cada consulta hoje vai ao banco, a fonte da verdade que o gravador protege. No pico, isso é o banco apanhando de milhões de leituras idênticas. Cache é a resposta — mas cache é uma faca de dois gumes: o cache certo economiza banco e dinheiro; o cache errado serve dado velho e gera o bug mais difícil de reproduzir do seu sistema. Esta aula é sobre fazer o primeiro e evitar o segundo, com consciência dos trade-offs.

---

## 1. Por que cachear (e por que não é grátis)

Três motivos, em ordem de impacto:

- **Latência.** Uma leitura do banco atravessa pool de conexão, rede, parser de SQL, índice e disco. Uma leitura de cache em memória é ordens de magnitude mais rápida. Para o cliente, é a diferença entre o comprovante "aparecer na hora" e "demorar".
- **Alívio do banco.** A fonte da verdade é o recurso mais caro de escalar (escalar banco relacional horizontalmente é difícil e caro). Cada *hit* no cache é uma query que **não** chegou ao banco. No pico, é a diferença entre o banco respirar e o banco cair.
- **Custo.** Capacidade de banco custa caro; capacidade de cache é barata. Servir leitura quente de cache é, literalmente, mais barato por requisição.

Mas cache **não é grátis**. Você introduz uma **segunda cópia da verdade**, e duas cópias podem divergir. A engenharia de cache é, no fundo, uma única decisão repetida: **o que pode estar um pouco velho — e por quanto tempo?** Se a resposta para algum dado é "nada pode estar velho, nunca", esse dado não deveria ser cacheado. Para o comprovante já gravado — que é **imutável** depois de gravado — a resposta é "pode estar velho à vontade, ele não muda", e isso o torna um candidato quase perfeito.

---

## 2. Cache local × distribuído: por que Redis e não um `HashMap`

A tentação do iniciante é guardar num `HashMap` (ou Caffeine) dentro do processo. É rápido e zero-infra. E quebra assim que você tem **mais de uma instância** — que é o caso de qualquer sistema sério na nuvem, com várias réplicas atrás de um load balancer.

| Aspecto | Cache local (HashMap/Caffeine) | Cache distribuído (Redis) |
|---|---|---|
| Latência | Menor (mesma JVM, sem rede) | Baixa, mas paga um *hop* de rede |
| Compartilhado entre réplicas | **Não** — cada nó tem sua visão | **Sim** — visão única |
| Sobrevive ao restart do nó | Não (morre com o processo) | Sim (estado fora do nó) |
| Invalidação consistente | Difícil (invalida só num nó) | Simples (uma chave, todos veem) |
| Estado preso ao nó | Sim (impede escala fácil) | Não (nó vira *stateless*) |

Os dois problemas do cache local são fatais em ambiente replicado:

- **Visões divergentes.** A instância A cacheou o comprovante; a instância B nunca o viu. O cliente, atrás do load balancer, vê respostas diferentes dependendo de qual nó atendeu. Pior: invalidar na instância A **não afeta** a B — a B segue servindo o dado velho.
- **Estado preso ao nó.** Guardar estado dentro do processo te força a *sticky session* (prender o cliente a um nó), o que **mata a escala horizontal** — você não pode simplesmente adicionar/remover nós livremente.

Redis resolve os dois: é um cache **fora do processo**, **compartilhado** por todas as réplicas. Ele tira o estado do nó (adeus *sticky session*), o que é **pré-requisito** para escalar horizontalmente — exatamente o que a consulta de comprovante precisa no pico.

---

## 3. Estratégias de cache: saiba escolher, não decore

Cache não é uma coisa só; é um conjunto de estratégias com trade-offs distintos. Conheça as quatro e saiba **quando** usar cada uma.

- **Cache-aside (lazy loading).** A aplicação é responsável pelo cache: consulta o cache; no *miss*, vai ao banco, **popula** o cache e retorna. O cache só guarda o que foi pedido (carrega "preguiçosamente"). É a estratégia mais comum, e a do nosso projeto PIX. **Vantagem:** simples, resiliente (se o Redis cai, você ainda lê do banco). **Custo:** o primeiro acesso a cada chave é sempre um *miss* (cache "frio").

- **Read-through.** Parecido com cache-aside, mas é o **provedor de cache** quem busca no banco no *miss*, não a aplicação. A aplicação só fala com o cache. **Vantagem:** lógica de carga centralizada. **Custo:** acopla você à biblioteca de cache.

- **Write-through.** Na escrita, grava no cache **e** no banco, sincronamente, juntos. **Vantagem:** o cache está sempre quente e consistente com o banco — leituras nunca pegam dado velho. **Custo:** escrita mais lenta (paga as duas gravações) e cacheia dado que talvez nunca seja lido.

- **Write-behind (write-back).** Grava no cache e persiste no banco **depois**, de forma assíncrona. **Vantagem:** escrita rapidíssima, absorve picos de escrita. **Custo:** **risco de perda** — se o cache cai antes de persistir, o dado se foi. Inaceitável para a fonte da verdade de um comprovante; aceitável para métricas/contadores onde perder um pouco não dói.

Heurística: **cache-aside para o caso de leitura pesada e escrita rara** (o comprovante imutável); **write-through quando consistência de leitura é crítica e a escrita aguenta o custo**; **write-behind só para dados toleráveis a perda**.

### Em código, com Spring Cache

```java
@Service
public class ConsultaComprovanteService {

    private final ComprovanteRepository repo;

    public ConsultaComprovanteService(ComprovanteRepository repo) {
        this.repo = repo;
    }

    // Cache-aside: só executa o corpo no MISS; o retorno popula a chave automaticamente.
    @Cacheable(value = "comprovantes", key = "#id")
    public Comprovante buscar(UUID id) {
        return repo.findById(id)
                   .orElseThrow(() -> new ComprovanteNaoEncontrado(id));
    }

    // Invalidação explícita: ao mudar o comprovante, remove a entrada do cache.
    @CacheEvict(value = "comprovantes", key = "#c.id")
    public void atualizar(Comprovante c) {
        repo.save(c);
    }
}
```

> No projeto, a **mesma anotação** roda sobre **Caffeine / embedded-redis** (ambiente pura-JVM, sem Docker) ou sobre **Redis** distribuído (produção): o código de domínio não muda, só o `CacheManager` injetado. Programar contra a **abstração** Spring Cache é o que te dá essa portabilidade — você não amarra a regra de negócio a uma tecnologia de cache, e o mesmo código sobe sem reescrita num serviço Quarkus/Spring com Redis de verdade.

---

## 4. TTL e as três formas de invalidação

> *"Só existem duas coisas difíceis em computação: invalidação de cache e nomear coisas."* — Phil Karlton

A piada é verdadeira porque invalidação é onde a segunda cópia da verdade **mente**. Você tem três caminhos, e a escolha deve ser consciente (registre num ADR):

- **TTL (time-to-live).** A entrada **expira sozinha** após um tempo. Simples e robusto: aceita uma janela de inconsistência conhecida e limitada. É a rede de segurança — mesmo que você esqueça de invalidar, o dado velho some sozinho. Para o comprovante imutável, um TTL generoso (horas) é seguro, porque o dado não muda.

  ```java
  // application.yml — TTL por cache, com Redis
  // spring.cache.redis.time-to-live: 1h
  ```

- **Evicção explícita.** No *update*, você **remove** a entrada (`@CacheEvict`). Preciso — o dado novo entra no próximo *miss* — mas exige **disciplina**: todo caminho que altera o dado precisa lembrar de invalidar. Esqueça um, e o cache serve dado velho indefinidamente. (Por isso o TTL como rede de segurança em cima.)

- **Versionamento de chave.** Em vez de invalidar, você **muda a chave**. A chave inclui uma versão (`comprovante:v2:{id}`); ao "invalidar", você incrementa a versão, e as leituras passam a buscar a chave nova (que dá *miss* e repopula), enquanto a antiga expira por TTL. Elimina o problema de "esquecer de invalidar em algum caminho", ao custo de gerenciar a versão.

Por que invalidação é difícil, no fundo: **não existe o momento "agora todos sabem"** num sistema distribuído. Entre o dado mudar no banco e a invalidação propagar, há uma janela em que o cache mente. Você não elimina essa janela — você a **escolhe e a limita** (com TTL) ou a **contorna** (com versionamento).

---

## 5. Stampede / thundering herd

Considere uma chave **quente** (muito acessada) que **expira**. No instante seguinte, mil requisições simultâneas dão *miss* ao mesmo tempo e **todas** batem no banco para recomputar a mesma coisa. O banco, que estava tranquilo servindo do cache, leva uma pancada súbita — pode até cair. Isso é o **cache stampede** (ou *thundering herd*).

É o detalhe que separa "cache que funcionou na demo" de "cache que aguenta produção". Três mitigações:

- **Lock de recomputação.** Quando a chave expira, **apenas uma** requisição adquire um *lock* e vai ao banco recomputar; as outras esperam o resultado dela (ou servem o valor antigo por um instante). Evita a avalanche, ao custo de um pouco de coordenação.
- **TTL com jitter.** Em vez de TTL fixo (todas as chaves quentes expirando juntas), some um valor **aleatório** ao TTL (`1h ± 5min`). As expirações se **espalham** no tempo, e nunca há um instante em que tudo expira de uma vez. Barato e muito eficaz.
- **Request coalescing.** Requisições idênticas e concorrentes para a mesma chave são **fundidas** numa só ida ao banco; todas recebem o mesmo resultado. Variação do lock, no nível da aplicação.

```java
// TTL com jitter: espalha expirações para evitar stampede.
private Duration ttlComJitter() {
    long base = Duration.ofHours(1).toSeconds();
    long jitter = ThreadLocalRandom.current().nextLong(0, 300); // até +5 min
    return Duration.ofSeconds(base + jitter);
}
```

---

## 6. Consistência cache↔banco e métricas

**Consistência.** O cache é uma segunda cópia; a verdade é o banco. A regra de ouro: **o banco é a fonte; o cache é descartável.** Se houver dúvida, o cache deve poder ser jogado fora e reconstruído a partir do banco a qualquer momento, sem perda. Isso te dá uma propriedade preciosa: se o Redis cair, o sistema **degrada** (mais lento, banco mais sobrecarregado) mas **não erra** — continua lendo a verdade do banco. Cache nunca deve ser a única cópia de algo que você não pode perder.

**Métricas.** Sem medir, **você não sabe se o cache ajuda ou só esconde bug**. As duas métricas essenciais:

- **Hit ratio** = hits / (hits + misses). Um cache com hit ratio baixo (digamos, 20%) está pagando o custo de manutenção e consistência sem entregar o benefício — provavelmente você está cacheando dado que muda demais, ou com TTL curto demais. Se o hit ratio é alto (90%+), o cache está fazendo seu trabalho.
- **Miss penalty** — quanto custa um *miss* (a ida ao banco). Junto com o hit ratio, te diz o impacto real no p99.

Instrumente desde o dia 1. Spring Cache + Micrometer expõem `cache.gets` com tag `result=hit|miss` automaticamente; ligue isso a um dashboard. "Funcionou na demo" não é métrica.

---

## 7. Exemplo trabalhado: a consulta de comprovante PIX

Vamos montar o fluxo de consulta do projeto, ponta a ponta, e discutir a decisão mais sutil: **o que fazer quando o comprovante não está em lugar nenhum**.

O fluxo desejado:

```
   GET /comprovantes/{id}
            │
            ▼
   ┌────────────────┐  HIT   ┌──────────────────┐
   │  cache (Redis) │ ─────► │ 200 + comprovante│
   └───────┬────────┘        └──────────────────┘
           │ MISS
           ▼
   ┌────────────────┐  achou ┌──────────────────────────┐
   │gravador (fonte)│ ─────► │ popula cache + 200       │
   └───────┬────────┘        └──────────────────────────┘
           │ não achou
           ▼
   ┌──────────────────────────────────────────┐
   │  3 retentativas (janela da gravação       │
   │  assíncrona — Aula 2)                     │
   └───────┬──────────────────────────────────┘
           │ ainda não achou
           ▼
        404 Not Found   (NÃO cacheia o 404)
```

Por que os **3 retries**? Por causa da consistência eventual da Aula 2. O cliente fez o PIX, recebeu `202`, e consulta **imediatamente** — mas a gravação assíncrona pode ainda estar na janela de processamento. Um `404` aí seria mentira ("seu comprovante não existe") quando a verdade é "ainda não terminou de gravar". As retentativas (com pequeno *backoff*) cobrem essa janela: re-consulta o gravador (a fonte do comprovante) algumas vezes antes de declarar ausência.

**A decisão de ouro: por que NÃO cachear o 404.** Suponha que você cacheasse a ausência ("não achei, guarda 404"). O comprovante chega à base 200ms depois (a gravação terminou) — mas o cache, durante todo o TTL, continua respondendo `404` para um comprovante que **agora existe**. Você transformou uma janela de milissegundos num bug de minutos. Em domínio bancário, "seu comprovante de PIX não existe" sendo servido por um cache desatualizado é exatamente o tipo de erro que gera ticket, reclamação e desconfiança. **Regra: cacheie a presença (o comprovante gravado, imutável), nunca a ausência transitória.**

```java
@Service
public class ConsultaComprovante {

    private static final int MAX_TENTATIVAS = 3;

    private final GravadorClient gravador; // a fonte do comprovante
    private final ComprovanteCache cache;  // cache-aside sobre Redis
    private final Duration backoffInicial;

    public ConsultaComprovante(GravadorClient gravador, ComprovanteCache cache,
                               @Value("${consulta.backoff-inicial}") Duration backoffInicial) {
        this.gravador = gravador;
        this.cache = cache;
        this.backoffInicial = backoffInicial;
    }

    public ComprovanteDTO consultar(UUID id) {
        return cache.get(id)                       // 1. tenta o cache
            .or(() -> buscarComRetentativas(id))   // 2. miss → gravador com retries
            .orElseThrow(() -> new ComprovanteNaoEncontrado(id)); // 3. 404 — não cacheado
    }

    private Optional<ComprovanteDTO> buscarComRetentativas(UUID id) {
        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS; tentativa++) {
            Optional<ComprovanteDTO> achado = gravador.buscarPorId(id);
            if (achado.isPresent()) {
                cache.put(id, achado.get());       // só popula o cache na PRESENÇA
                return achado;
            }
            esperarBackoff(tentativa);             // janela da gravação assíncrona
        }
        return Optional.empty();                    // ausência real → 404, sem cachear
    }

    /** Backoff exponencial: backoffInicial, 2x, 4x... */
    private void esperarBackoff(int tentativa) {
        try {
            Thread.sleep(backoffInicial.multipliedBy(1L << (tentativa - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

---

## 8. Armadilhas comuns

- **Cachear dado que muda toda hora.** Hit ratio baixo (pouco reúso) e risco de inconsistência alto — o pior dos dois mundos. Cache brilha em "lê muito, muda pouco".
- **TTL infinito "porque é mais rápido".** Sem expiração e sem invalidação, o dado velho fica para sempre. TTL é sua rede de segurança; nunca abra mão dela.
- **Não medir hit/miss.** Sem métrica, você está apostando, não engenheirando. Pode estar pagando custo de cache sem benefício — e nem sabe.
- **Cachear a ausência transitória (o 404).** Transforma uma janela de eventual consistency num bug persistente. Cacheie presença, não ausência.
- **Tratar o cache como fonte da verdade.** Se perder o cache te faz perder dado, você não tem cache — tem um banco frágil. Cache é sempre descartável.

---

## 9. Ponte com o legado Caixa

Muita aplicação legado já "cacheia" — só que mal. O padrão clássico é cachear em **tabela de banco** (uma tabela "de apoio" lida no lugar da consulta cara) ou na **memória do *app server*** com **sessão pegajosa**. Funciona maravilhosamente bem... até você precisar da **segunda instância**. Aí as visões divergem (cada *app server* com seu cache), a invalidação numa não chega na outra, e aparece o bug fantasma "funciona num servidor e não no outro".

Redis é a **evolução natural** desse instinto que o veterano já tem: é o mesmo conceito de "guardar o resultado caro para reusar", mas **fora do processo** e **compartilhado** entre todas as instâncias. O veterano que entende por que a sessão pegajosa quebra em escala entende, sem esforço, por que Redis existe — e vira o melhor revisor de decisões de cache da sala.

---

## 10. IA & agentes hoje

Cache deixou de ser "otimização" e virou **requisito econômico** quando a coisa cara de computar passou a ser uma chamada de LLM, não uma query:

- **Semantic caching.** Respostas de LLM podem ser cacheadas por **similaridade de embedding**, não por chave exata. Duas perguntas com palavras diferentes mas **significado parecido** ("qual meu saldo?" / "quanto tenho na conta?") reusam a mesma resposta cacheada. Corta latência e, principalmente, **custo** — você não paga uma inferência nova para uma pergunta semanticamente repetida.
- **Redis como vector store.** O mesmo Redis que cacheia comprovante serve de **índice vetorial** para **RAG** (Retrieval-Augmented Generation): você guarda embeddings de documentos e busca por similaridade os trechos relevantes para alimentar o prompt. Cache e busca vetorial convivem na mesma infra.
- **Cache como requisito econômico da inferência.** Uma chamada de LLM é **ordens de magnitude** mais cara e lenta que uma leitura de banco. No mundo transacional, cache economizava milissegundos e capacidade de banco; no mundo de IA, cache economiza **dólares por requisição** e segundos de espera. A mentalidade muda: cache deixa de ser "se sobrar tempo, otimizo" e passa a ser parte do **design de custo** do sistema desde o primeiro dia.

E note a continuidade com a Aula 2: a saída de um passo de agente é **não-determinística**, então cachear essa saída pela chave da tarefa serve a **dois** propósitos — economia (não rechamar o modelo) e **idempotência** (o *retry* devolve o mesmo resultado, sem repetir efeito).

---

## 11. Para ir além

- **Documentação Redis** — *Caching strategies* e *Key eviction / TTL* (redis.io/docs): as estratégias da §3 e a invalidação da §4, da fonte.
- **Martin Fowler**, *Patterns of Enterprise Application Architecture* — os padrões de cache e *Lazy Load*.
- **Spring Framework** — *Cache Abstraction* (`@Cacheable`, `@CacheEvict`, `CacheManager`): a portabilidade Redis↔Caffeine do projeto.
- **Redis as a Vector Database** — docs de busca vetorial e *semantic caching* para RAG.

> **Na próxima aula (Aula 5 — producer/consumer):** o cache aliviou a leitura, mas a **escrita** continua com um gargalo de design: o gravador é mais lento e mais frágil que a emissão. Por que fazer a emissão **esperar** por ele? Entra o processamento **assíncrono** — a fila que desacopla emissão de gravação, transforma o `202 Accepted` numa promessa honesta e fecha o circuito que a SAGA da Aula 2 desenhou.
