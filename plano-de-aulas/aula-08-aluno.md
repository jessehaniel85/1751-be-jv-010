# Material do Aluno — Aula 8: Resiliência — retry, circuit breaker e `@RetryableTopic`

> **Tempo de leitura:** ~13 min. Esta aula é sobre uma verdade desconfortável: num sistema distribuído, **as falhas não são exceção, são rotina**. A rede oscila, dependências ficam lentas, serviços externos retornam 503 sem aviso. A pergunta não é "como evito falhas?" — é "como o meu sistema **falha de propósito**, de um jeito controlado, antes que decida por você do pior jeito?". Leia com cuidado a seção de idempotência: ela é a pré-condição de tudo o que vem depois. Retry sem idempotência não é resiliência, é duplicação de efeito.

---

## 1. O problema: reentregar sem afundar o navio

No nosso projeto-guia, o consumidor de **notificação** lê o tópico `comprovante-gravado` (Aula 6) e, para cada evento, chama um **gateway externo** de notificação (SMS/push). Esse gateway é de terceiro e **oscila**: às vezes responde em 80 ms, às vezes timeout, às vezes retorna 503 por dois minutos durante uma instabilidade.

Queremos três coisas ao mesmo tempo, e elas tensionam entre si:

1. **Reentregar** quando o gateway se recupera (não perder a notificação por uma falha passageira).
2. **Parar de tentar** quando ele claramente caiu (não martelar uma dependência morta).
3. **Não inundá-lo** com retentativas que só aprofundam o buraco.

O instinto do júnior é envolver a chamada num `try/catch` e tentar de novo num loop. Isso resolve o caso 1 e **piora** os casos 2 e 3 — é a receita do incidente. Resiliência é decidir, **de antemão e explicitamente**, como o sistema se comporta sob falha. As próximas seções são esse repertório de decisões.

---

## 2. As falácias da computação distribuída

Em 1994, Peter Deutsch e colegas da Sun catalogaram as **falsas suposições** que todo desenvolvedor faz ao construir sistemas distribuídos — e que cobram caro em produção. Vale conhecê-las pelo nome, porque cada padrão desta aula existe para **contrariar uma delas**:

1. **A rede é confiável.** É a falácia-mãe. A rede cai, perde pacotes, particiona. → exige **retry**.
2. **A latência é zero.** Chamar um serviço remoto não é como chamar um método local; leva tempo, e esse tempo varia. → exige **timeout**.
3. **A banda é infinita.** Mandar muito dado satura. → exige **rate limiting** e backpressure.
4. **A rede é segura.** Nunca é. → exige autenticação, criptografia.
5. **A topologia não muda.** Instâncias sobem e descem o tempo todo (lembre do rebalance da Aula 6). → exige descoberta de serviço, idempotência.
6. **Há um único administrador.** Dependências de terceiros (o gateway de notificação, uma API de LLM) mudam sem te avisar. → exige **circuit breaker** e **fallback**.
7. **O custo de transporte é zero.** Serialização, rede e infra têm custo. → exige eficiência.
8. **A rede é homogênea.** Protocolos, versões e comportamentos divergem.

O fio condutor: **assumir que tudo dá certo é um bug de design.** O código resiliente assume que a chamada remota **vai falhar** e decide o que fazer quando falhar.

---

## 3. Retry: o veneno e o antídoto (retry storm)

Retry é a resposta natural à falácia "a rede é confiável": se uma falha pode ser transitória, tente de novo. Mas o retry **ingênuo** — tentar de novo imediatamente, em loop, sem limite — é uma das formas mais rápidas de transformar uma instabilidade pequena num apagão.

O mecanismo do desastre é o **retry storm** (tempestade de retentativas). Imagine que o gateway fica lento por sobrecarga. Mil consumidores recebem timeout **ao mesmo tempo** e, todos juntos, tentam de novo **imediatamente**. O gateway, que estava só sobrecarregado, agora recebe o **dobro** de tráfego no mesmo instante e morre de vez. Quando volta a respirar, os mil clientes tentam de novo, sincronizados, e o derrubam outra vez. O retry, que deveria ajudar, virou um **ataque de negação de serviço involuntário** contra a própria dependência.

Os três antídotos formam a base de qualquer política de retry decente:

- **Backoff exponencial.** Espace as tentativas de forma crescente: 1s, 2s, 4s, 8s. Dá tempo de a dependência se recuperar em vez de ser martelada.
- **Jitter (aleatoriedade).** Backoff sozinho não basta: se mil clientes falham juntos, eles ainda tentam de novo **juntos** em 1s, depois juntos em 2s — a sincronia persiste. O jitter soma uma aleatoriedade ao intervalo para **espalhar** as retentativas no tempo. Tipos comuns: *full jitter* (intervalo aleatório entre 0 e o backoff calculado — máximo espalhamento), *equal jitter* (metade fixa + metade aleatória) e *decorrelated jitter* (o próximo intervalo deriva aleatoriamente do anterior). Na prática, **full jitter** costuma ser a escolha mais simples e eficaz.
- **Limite de tentativas.** Nunca tente para sempre. Depois do teto (digamos, 4 tentativas), a mensagem vai para a **DLT/DLQ** (Aula 5) para análise humana. Insistir infinitamente é desperdiçar recurso e mascarar uma falha real.

> A diferença entre 1, 2, 4, 8 segundos **com jitter** e 0, 0, 0, 0 segundos **sem limite** é a diferença entre um sistema que se recupera sozinho e um que entra em colapso. Retry é necessário; retry **disciplinado** é o que separa engenharia de gambiarra.

---

## 4. Idempotência: a pré-condição de qualquer retry

Aqui está a regra que não pode ser negociada: **você só pode tentar de novo com segurança se a operação for idempotente.** Uma operação é **idempotente** quando executá-la N vezes tem o **mesmo efeito** que executá-la uma vez.

Por que isso é vital? Considere o pior cenário do retry: o consumidor de notificação chama o gateway, o gateway **processa e envia o SMS**, mas a **resposta se perde** na rede (timeout do lado do cliente). O consumidor acha que falhou e **tenta de novo**. Sem proteção, o cliente recebe **dois SMS**. Em outro domínio — "debitar R$ 100" — o retry de uma resposta perdida debitaria **R$ 200**. O retry, que existe para não perder a operação, acabou **duplicando** o efeito.

A defesa é tornar a operação idempotente, tipicamente com uma **chave de idempotência** — um identificador único da operação que o receptor usa para detectar repetição:

```java
@Service
public class NotificacaoService {

    private final NotificacaoRepository repository;
    private final GatewayCliente gateway;

    public void avisar(ComprovanteGravadoEvent e) {
        // chave natural: o id do comprovante. Já notificado? não repete.
        if (repository.jaNotificado(e.comprovanteId())) {
            return; // idempotente: segunda execução não tem efeito adicional
        }
        gateway.enviar(e.chavePixDestino(), e.comprovanteId()).join(); // espera o envio antes de seguir
        repository.marcarNotificado(e.comprovanteId());
    }
}
```

No nosso domínio, o `comprovanteId` é a chave natural — ele identifica unicamente o fato. Repare como tudo se conecta: na Aula 6 escolhemos o `id` do comprovante como **chave de partição** (ordem) e agora ele é também a **chave de idempotência** (deduplicação). O Kafka entrega *at-least-once* por padrão (pode reentregar no rebalance), então **idempotência no consumidor não é opcional** — é o que torna o "pelo menos uma vez" seguro.

---

## 5. Circuit breaker: falhar rápido para se proteger

Retry resolve falhas **transitórias** (um soluço de 200 ms). Mas e quando a dependência caiu **de verdade** e vai ficar fora por minutos? Continuar tentando — mesmo com backoff — desperdiça threads, enche timeouts e pode **propagar a falha em cascata** para o seu próprio serviço. A defesa é o **circuit breaker** (disjuntor), inspirado no disjuntor elétrico: quando a corrente está perigosa, ele **abre** e corta o circuito antes que a casa pegue fogo.

O circuit breaker monitora a taxa de falha das chamadas a uma dependência e transita entre três estados:

| Estado | Comportamento | Transição |
|---|---|---|
| **Closed** | Tudo normal; chamadas passam. Falhas são contabilizadas numa janela. | Se a taxa de falha estoura o limiar → **Open** |
| **Open** | Circuito aberto: chamadas **falham na hora** (sem nem tentar a dependência). Poupa você e dá fôlego à dependência doente. | Após `wait-duration` → **Half-Open** |
| **Half-Open** | Deixa passar **algumas** chamadas de teste. | Se voltam OK → **Closed**; se falham → **Open** de novo |

O conceito contraintuitivo é **"falhar rápido"**. Quando o circuito está aberto, sua chamada falha **imediatamente** em vez de esperar um timeout de 30 segundos. Isso parece pior ("nem tentei!"), mas é o que **evita esgotar o pool de threads**: 1.000 requisições presas esperando timeout de uma dependência morta congelam o serviço inteiro e derrubam **funcionalidades que nada têm a ver** com o gateway. Falhar rápido contém o dano. Os parâmetros que você configura:

- **Sliding window** — a janela (por contagem ou por tempo) sobre a qual a taxa de falha é medida.
- **Failure rate threshold** — o percentual de falhas que dispara a abertura (ex.: 50%).
- **Wait duration in open state** — quanto tempo fica aberto antes de testar (Half-Open).

```java
// application.yml (Resilience4j)
//
// resilience4j:
//   circuitbreaker:
//     instances:
//       gatewayNotificacao:
//         sliding-window-type: COUNT_BASED
//         sliding-window-size: 10
//         failure-rate-threshold: 50          # 50% de falhas → abre
//         wait-duration-in-open-state: 30s     # 30s aberto, depois half-open
//         permitted-number-of-calls-in-half-open-state: 3

@Service
public class GatewayCliente {

    @CircuitBreaker(name = "gatewayNotificacao", fallbackMethod = "fallback")
    public CompletableFuture<Void> enviar(String destino, UUID comprovanteId) {
        gatewayHttp.post(destino, comprovanteId); // pode estourar timeout / 503
        return CompletableFuture.completedFuture(null);
    }

    // chamado quando o circuito está aberto OU a chamada falha
    private CompletableFuture<Void> fallback(String destino, UUID comprovanteId, Throwable t) {
        log.warn("Gateway indisponível ({}). Enfileirando para reenvio: {}", t.getMessage(), comprovanteId);
        reenvioRepository.agendar(comprovanteId); // degrada com elegância, não derruba
        return CompletableFuture.completedFuture(null);
    }
}
```

O **fallback** é o par natural do breaker: quando o circuito abre, em vez de explodir, você executa um plano B (enfileirar para depois, retornar uma resposta degradada, usar cache). Falhar de propósito **com** um plano B é o auge da resiliência.

---

## 6. Timeout, bulkhead e rate limiting

O circuit breaker não vem sozinho. Três padrões completam o kit, cada um atacando uma falácia da seção 2.

**Timeout.** Contraria "a latência é zero". **Toda** chamada remota precisa de um teto de tempo. Sem timeout, uma dependência que ficou lenta (não caiu — só lenta) prende suas threads indefinidamente, e o efeito é idêntico ao de uma queda total. Regra: **nunca espere para sempre.** O timeout é também o que **alimenta** o circuit breaker — uma chamada que estoura o timeout conta como falha na janela.

**Bulkhead (anteparo).** O nome vem dos compartimentos estanques de um navio: se um enche d'água, os anteparos impedem que afunde o casco inteiro. Em software, é **isolar pools de recursos por dependência**. Se as chamadas ao gateway de notificação têm seu próprio pool de threads, uma lentidão lá esgota **só aquele pool** — as chamadas à antifraude, que usam outro pool, continuam normais. Sem bulkhead, uma dependência doente consome **todas** as threads do serviço e derruba tudo junto.

```java
@Bulkhead(name = "gatewayNotificacao", type = Bulkhead.Type.THREADPOOL)
@CircuitBreaker(name = "gatewayNotificacao", fallbackMethod = "fallback")
public CompletableFuture<Void> enviar(String destino, UUID comprovanteId) {
    gatewayHttp.post(destino, comprovanteId);
    return CompletableFuture.completedFuture(null);
}
```

O bulkhead do tipo `THREADPOOL` só se aplica a métodos que devolvem `CompletableFuture`; num método `void`, o Resilience4j lança `IllegalStateException` (e o fallback do circuit breaker engole o erro).

**Rate limiting (brevemente).** Contraria "a banda é infinita". Limita **quantas** chamadas você faz por unidade de tempo a uma dependência. Útil quando o terceiro impõe cota (o gateway aceita 100 req/s) ou quando você quer proteger uma dependência frágil de picos. No Resilience4j é o `@RateLimiter`; conceitualmente, é você **se autolimitando** antes que a dependência te puna com 429.

---

## 7. `@RetryableTopic`: retry e DLT no Spring Kafka

Os padrões acima valem para chamadas síncronas. Mas o consumidor de notificação é **orientado a eventos** — ele lê o tópico Kafka. Aqui há um problema específico: se uma mensagem falha e você fica tentando **dentro** do listener, você **trava o consumo** da partição inteira (lembre: ordem por partição, Aula 6). Uma mensagem problemática bloqueia todas as seguintes — *head-of-line blocking*.

O Spring Kafka resolve isso com `@RetryableTopic`, que implementa retry **não-bloqueante** usando **tópicos auxiliares**. A mensagem que falha não é reprocessada em loop no mesmo lugar; ela é **republicada** num tópico de retry com delay, liberando a partição principal:

```java
@Component
public class NotificacaoListener {

    private final NotificacaoService notificacaoService;

    @RetryableTopic(
            attempts = "4",                                  // 1 original + 3 retries
            backoff = @Backoff(delay = 1000, multiplier = 2.0, random = true), // ~1s, 2s, 4s, com jitter
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE, // -retry-0, -1, -2 (com jitter, o atraso não serve de nome)
            dltStrategy = DltStrategy.FAIL_ON_ERROR)          // esgotou → DLT
    @KafkaListener(topics = "comprovante-gravado", groupId = "notificacao")
    public void aoGravar(ComprovanteGravadoEvent e) {
        notificacaoService.avisar(e); // idempotente (seção 4) → retry seguro
    }

    @DltHandler
    public void tratarDlt(ComprovanteGravadoEvent e) {
        // teto de tentativas atingido: parar e pedir olhar humano
        alertaService.notificarFalhaPermanente(e.comprovanteId());
    }
}
```

O que o Spring cria automaticamente nos bastidores:

```
comprovante-gravado            (principal)
comprovante-gravado-retry-0    (delay 1s)
comprovante-gravado-retry-1    (delay 2s)
comprovante-gravado-retry-2    (delay 4s)
comprovante-gravado-dlt        (dead-letter topic: parou aqui)
```

A mensagem desce essa escada de retry com backoff exponencial; se todas as tentativas falham, cai na **DLT** — o "fim da linha" onde a equipe investiga sem perder o evento. Repare como `@RetryableTopic` (retry no nível da entrega da mensagem) e `@CircuitBreaker` (proteção da chamada externa dentro do `avisar`) **se compõem**: o breaker evita que cada tentativa de retry martele um gateway morto, e o retry garante que falhas transitórias não percam a notificação. E nada disso é seguro sem a **idempotência** da seção 4 — porque retry, por definição, reexecuta.

---

## 8. Exemplo trabalhado: o caminho completo de uma notificação

Juntando as peças, vamos seguir um evento `comprovante-gravado` em três cenários.

**Cenário A — gateway saudável.** O listener recebe o evento, `avisar` verifica que não foi notificado, o circuit breaker está *closed*, o gateway responde OK, marca como notificado. Uma execução, um SMS.

**Cenário B — falha transitória (gateway soluça).** Timeout na primeira tentativa. `@RetryableTopic` republica no `retry-0` com 1s de delay; na segunda, o gateway respondeu OK. A idempotência garante que, se o SMS tinha sido enviado mas a resposta se perdeu, o `jaNotificado` impede o segundo envio. Notificação entregue, **sem duplicação**, sem travar a partição.

**Cenário C — falha permanente (gateway fora há minutos).** As primeiras falhas enchem a sliding window; ao cruzar 50%, o **circuit breaker abre**. Cada tentativa passa a **falhar na hora** (sem esperar timeout) e cai no `fallback`, que **enfileira para reenvio** em vez de explodir. Esgotadas as 4 tentativas, a mensagem vai para a **DLT** e o `@DltHandler` dispara um alerta. O serviço **continua de pé**, sem esgotar threads e sem derrubar antifraude e BI (isolados por bulkhead + consumer groups separados). Quando o gateway volta, o breaker passa a *half-open*, testa, fecha, e o reenvio é drenado.

Os três cenários com a **mesma** configuração. Isso é "decidir como falhar de propósito": o comportamento sob falha não é improviso — está desenhado.

---

## 9. Ponte com o legado Caixa

Quem operou o batch noturno já fez resiliência sem o vocabulário moderno. O **"reprocessamento do job que deu erro"** é o ancestral do retry; os **"limites de retentativa"** configurados no agendador de jobs são o teto de tentativas; o **"arquivo de rejeitados"** que sobrava para análise no dia seguinte é a **DLT** dos nossos dias. Até o circuit breaker tem parente lá: o operador que, vendo um sistema downstream fora do ar, **suspendia o job** em vez de deixá-lo falhar a noite inteira contra uma dependência morta — exatamente o "falhar rápido" automatizado.

A diferença é o **regime**: no legado, a resiliência era **batch, manual e no dia seguinte** (rodar de novo o job de manhã). Hoje ela é **reativa, automática e por requisição** — o sistema decide em milissegundos se tenta de novo, se abre o circuito ou se manda para a DLT. Quem entende o ciclo de reprocessamento e rejeição do batch já tem o modelo mental certo; só muda a granularidade e a velocidade.

---

## 10. IA & agentes hoje

Resiliência deixou de ser tema de "infra" e virou requisito central de qualquer sistema que chama um **LLM** — porque as APIs de modelo são instáveis por natureza:

- **APIs de LLM falham o tempo todo.** Rate limit (429), timeouts em respostas longas, 5xx esporádicos sob carga. Um agente que chama um modelo sem retry com backoff, timeout e circuit breaker **não sobrevive em produção**. É exatamente o gateway instável da nossa notificação, com latências ainda maiores e cotas mais rígidas (rate limiting não é opcional).
- **Fallback de modelo.** Quando o circuito abre no modelo primário (caro/maior), o fallback **cai para um modelo secundário** (menor, mais barato, outro provedor) ou retorna uma resposta degradada — em vez de o agente travar. É o `fallbackMethod` da seção 5 aplicado à escolha de modelo.
- **Idempotência com saída não-determinística.** Aqui mora a parte difícil: reexecutar um passo de IA pode produzir **resultado diferente** (o modelo é não-determinístico). Retry "puro" não basta. A defesa é controlar por **id de tarefa** e **cachear a resposta** da primeira execução bem-sucedida: o retry de uma resposta perdida devolve a saída já gerada, em vez de gerar outra — protegendo contra duplicar efeito **e** custo de token. É a chave de idempotência da seção 4, agora indispensável porque a operação não é naturalmente repetível.

---

## 11. Para ir além

- **Resilience4j** — documentação de `Retry`, `CircuitBreaker`, `Bulkhead`, `TimeLimiter` e `RateLimiter` (a referência prática em Java).
- **Michael Nygard**, *Release It!* — a origem dos *Stability Patterns* (Circuit Breaker, Bulkhead, Timeout); leitura obrigatória.
- **Spring for Apache Kafka** — `@RetryableTopic`, *Non-Blocking Retries* e *Dead Letter Topics*.
- **AWS Architecture Blog** — *Exponential Backoff and Jitter* (a análise quantitativa dos tipos de jitter).
- **Peter Deutsch / L. Peter Deutsch & James Gosling** — *The Fallacies of Distributed Computing* (o catálogo da seção 2).

> **Na próxima aula:** seu sistema agora sobrevive a falhas de **infraestrutura** — rede, dependências, picos. Mas há uma falha que nenhum retry ou breaker pega: o **contrato** entre serviços. Você mudou o JSON que o `comprovante-gravador` espera, e o `emissor` continuou mandando o formato antigo. Compila, sobe, e quebra **em produção**. Como descobrir que você quebrou o consumidor **antes** de subir? Entra o *contract testing*.
