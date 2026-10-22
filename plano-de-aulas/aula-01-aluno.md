# Material do Aluno — Aula 1: DDD como ferramenta de projeto e extração de microsserviços

> **Tempo de leitura:** ~15 min. Este é o capítulo mais denso do módulo de propósito: o DDD é a **ferramenta** com que você vai decidir onde cortar um sistema em microsserviços. Quase todo erro de arquitetura distribuída que você verá nas próximas aulas — consistência, acoplamento, mensageria mal desenhada — nasce de uma fronteira mal escolhida aqui. Leia com calma; volte aos dois cenários do fim quando for modelar o seu projeto.

---

## 1. O problema que o DDD resolve (e o que ele *não* é)

"Quebrar o monólito em microsserviços" é a frase mais perigosa de uma migração. Quebrar pelo lugar errado troca um monólito por um **monólito distribuído**: os módulos continuam acoplados como antes, mas agora com latência de rede, falhas parciais e deploys que precisam ser coordenados entre si. Você pagou todo o custo da distribuição e não comprou nenhuma das vantagens.

A pergunta certa nunca é *"em quantos serviços eu divido?"*. É *"quais são as fronteiras de negócio que mudam por razões diferentes, em ritmos diferentes, sob responsabilidade de pessoas diferentes?"*. Responder isso é uma decisão de **domínio**, não de tecnologia — e é exatamente para isso que serve o **Domain-Driven Design (DDD)**.

Antes de avançar, três mal-entendidos comuns, porque você vai ouvir todos eles:

- **DDD não é arquitetura em camadas.** Ter `controller / service / repository` não é fazer DDD.
- **DDD não é um framework nem uma tecnologia.** Não se instala DDD; não há `starter` de DDD. É uma forma de **modelar** software guiada pelo negócio.
- **DDD não é só os "blocos táticos"** (entidade, value object, agregado). Esses blocos são a parte fácil. O valor real está na parte **estratégica**: descobrir o domínio, falar a linguagem do negócio e traçar fronteiras de contexto.

O DDD tem dois grandes níveis, e você precisa dos dois:

| Nível | O que entrega | Ferramentas |
|---|---|---|
| **Estratégico** | *Onde* estão as fronteiras (que viram microsserviços) | Domínio, subdomínios, linguagem ubíqua, **bounded context**, context mapping, **event storming** |
| **Tático** | *Como* modelar o que existe dentro de cada fronteira | **Entidades**, **value objects**, **agregados**, serviços de domínio, eventos de domínio |

Vamos do estratégico ao tático e, no fim, aplicar tudo em dois cenários completos.

---

## 2. Domínio, subdomínios e a linguagem ubíqua

**Domínio** é a área de atuação do negócio — o problema que o software existe para resolver. Para um banco, o domínio é serviços financeiros. O domínio inteiro é grande demais para modelar de uma vez, então o quebramos em **subdomínios**, e os classificamos por importância estratégica:

- **Core domain (núcleo):** é onde está a vantagem competitiva e o que diferencia o negócio. Merece seus melhores desenvolvedores e o modelo mais cuidadoso. Ex.: o motor de liquidação de pagamentos; a análise de risco de crédito.
- **Supporting subdomain (de apoio):** necessário, mas não diferencia. Ex.: emissão de comprovantes — importante, mas não é o que faz o banco ganhar mercado.
- **Generic subdomain (genérico):** problema resolvido igual em qualquer empresa; idealmente se compra pronto. Ex.: autenticação, envio de e-mail, geração de PDF.

Por que isso importa para microsserviços? Porque a classificação orienta **investimento e fronteira**: um core domain raramente deve ficar acoplado a um genérico; e um genérico é candidato natural a ser um serviço isolado (ou um SaaS de terceiro).

### Linguagem ubíqua

O maior gerador de bug em sistemas grandes não é o código — é a **ambiguidade de termos**. "Conta", "cliente", "comprovante", "transação" significam coisas diferentes para áreas diferentes, e quando o código mistura esses significados, surgem as regras de negócio contraditórias.

A **linguagem ubíqua** é um vocabulário único, rigoroso, **compartilhado entre o negócio e o time técnico**, e usado em todo lugar: na conversa, no documento, no nome da classe, no endpoint. Não é "tradução" de termos de negócio para termos técnicos — é o **mesmo** termo dos dois lados. Se o especialista diz "comprovante emitido", a classe se chama `Comprovante`, o evento se chama `ComprovanteEmitido`, e ninguém no código chama isso de `Receipt` ou `TransactionLog`.

> Regra prática: se você, lendo o código, não consegue ter uma conversa com o especialista de negócio usando os nomes que estão lá, sua linguagem ubíqua falhou.

A linguagem ubíqua **não é global** — ela vale dentro de uma fronteira. E é essa fronteira que chamamos de bounded context. Guarde isso, vamos chegar lá.

---

## 3. Os blocos de construção táticos

Dentro de uma fronteira, você modela o negócio com alguns blocos. Os três que mais importam:

### 3.1. Entidade

Uma **entidade** é um objeto com **identidade própria e ciclo de vida** — ela é "a mesma" ao longo do tempo, mesmo que seus atributos mudem. O que a define é o **identificador**, não os valores.

Um `Comprovante` é uma entidade: tem um `id` (UUID), e continua sendo o mesmo comprovante mesmo que seu status mude de "aceito" para "gravado". Dois comprovantes com todos os campos iguais, mas `id` diferente, são **comprovantes diferentes**.

```java
// Entidade: igualdade por identidade, não por atributos.
public class Comprovante {
    private final UUID id;          // identidade — nunca muda
    private StatusComprovante status; // estado — muda ao longo da vida
    // equals/hashCode baseados SOMENTE no id
}
```

### 3.2. Value Object (objeto de valor)

Um **value object (VO)** é um objeto **sem identidade**, definido **inteiramente pelos seus valores**, e **imutável**. Dois VOs com os mesmos valores são *o mesmo* — como dois números `42` são iguais. Você não "altera" um VO; você cria outro.

VOs são onde mora boa parte da regra de negócio e onde você elimina a "obsessão por tipos primitivos" (*primitive obsession*) — aquele código cheio de `String` e `BigDecimal` soltos, sem validação centralizada.

```java
// Value Object: imutável, igualdade por valor, validação no construtor.
public record ChavePix(TipoChave tipo, String valor) {
    public ChavePix {
        if (valor == null || valor.isBlank())
            throw new IllegalArgumentException("chave PIX vazia");
        // a regra do que é uma chave válida vive AQUI, não espalhada
    }
}

public record Dinheiro(BigDecimal valor, String moeda) {
    public Dinheiro {
        if (valor.signum() < 0) throw new IllegalArgumentException("valor negativo");
    }
    public Dinheiro soma(Dinheiro outro) {
        if (!moeda.equals(outro.moeda)) throw new IllegalArgumentException("moedas diferentes");
        return new Dinheiro(valor.add(outro.valor), moeda); // retorna NOVO objeto
    }
}
```

Heurística rápida: **se você se importa com "qual é este", é entidade; se só se importa com "o que é este", é value object.** Conta bancária é entidade; o valor em reais é value object.

### 3.3. Agregado e raiz de agregado

Aqui está o conceito que mais separa quem "leu sobre DDD" de quem sabe usar. Um **agregado** é um *cluster* de entidades e value objects que **mudam juntos** e precisam manter uma regra de consistência (uma **invariante**) entre si. O agregado tem uma **raiz (aggregate root)** — a única entidade pela qual o mundo externo pode mexer no agregado.

Regras de agregado:

- **A invariante é mantida dentro da fronteira do agregado.** Ex.: "a soma dos lançamentos de uma fatura é igual ao valor total da fatura" — isso só é garantível se `Fatura` for a raiz e os `Lancamentos` só forem alterados através dela.
- **Uma transação altera um agregado.** Se uma operação precisa alterar dois agregados de forma atômica, isso é um forte sinal de que ou a fronteira está errada, ou você precisa de **consistência eventual** entre eles (assunto da Aula 2 — SAGA).
- **Referência entre agregados é por id, não por objeto.** Um `Comprovante` referencia o `id` do cliente, não um objeto `Cliente` inteiro carregado na memória.

```java
// Raiz de agregado: protege a invariante; ninguém mexe nos lançamentos por fora.
public class Fatura {                       // aggregate root
    private final UUID id;
    private final List<Lancamento> lancamentos = new ArrayList<>();
    private Dinheiro total;

    public void adicionar(Lancamento l) {   // única porta de entrada
        if (this.fechada) throw new FaturaFechadaException();
        lancamentos.add(l);
        this.total = this.total.soma(l.valor());   // invariante recalculada aqui
    }
}
```

**Por que isso decide microsserviços?** Porque o agregado é a **menor unidade de consistência transacional**. A fronteira de um microsserviço precisa conter agregados inteiros — você nunca quebra um agregado entre dois serviços. Errar isso é a causa nº 1 de "preciso de transação distribuída para tudo".

> Há ainda **serviços de domínio** (lógica que não pertence naturalmente a uma entidade — ex.: "transferir entre duas contas") e **eventos de domínio** (fatos do passado: `ComprovanteGravado`), que serão centrais das Aulas 4–6. Por ora, fixe entidade / VO / agregado.

---

## 4. Bounded Context — o coração estratégico

Um **bounded context (contexto delimitado)** é a fronteira dentro da qual um modelo e sua linguagem ubíqua são consistentes e válidos. Fora dela, os mesmos termos podem significar outra coisa.

O exemplo clássico: "Cliente".

- No contexto de **Atendimento**, `Cliente` tem telefone, histórico de chamados, canal preferido.
- No contexto de **Crédito**, `Cliente` tem score, renda, limite, comprometimento.
- No contexto de **Pagamentos**, `Cliente` é quase só uma chave e uma conta.

É o **mesmo** ser humano no mundo real, mas **três modelos diferentes**, cada um correto no seu contexto. Forçar uma única classe `Cliente` com todos os atributos de todos os contextos é criar a "entidade Deus" — o maior sintoma de contextos colados.

### Bounded context é o mesmo que microsserviço?

Não automaticamente — e essa é uma confusão cara. O bounded context é uma fronteira **lógica** (de modelo/linguagem). O microsserviço é uma fronteira **física** (de deploy). A relação saudável é:

- **Um bounded context → um ou mais microsserviços.** Um contexto pode ser grande e, por razões não-funcionais (escala, time, segurança), virar mais de um serviço.
- **Nunca o contrário:** um microsserviço **não deve** abranger mais de um bounded context — isso recria o acoplamento que você queria evitar.

Ou seja: **comece pelo bounded context.** Ele é o seu "candidato a microsserviço". Depois refine com requisitos não-funcionais.

---

## 5. Context Mapping — como os contextos se relacionam

Identificar os contextos é metade do trabalho; a outra metade é mapear **como eles se relacionam** — o *context map*. Os padrões que mais aparecem no dia a dia:

- **Customer/Supplier (cliente/fornecedor):** o contexto downstream (cliente) depende do upstream (fornecedor), e o fornecedor considera as necessidades do cliente ao evoluir.
- **Conformist (conformista):** o downstream simplesmente aceita o modelo do upstream como vem (sem poder de negociação). Comum ao consumir um sistema legado ou de terceiro.
- **Anti-Corruption Layer (ACL — camada anticorrupção):** o downstream cria uma camada de tradução que **protege seu modelo** do modelo do upstream. Essencial ao integrar com legado: o "Cliente" estranho do mainframe é traduzido para o seu "Cliente" limpo na entrada.
- **Open Host Service / Published Language:** o upstream expõe um contrato público bem definido (uma API/evento versionado) que vários downstreams consomem. É para onde miram os contratos das Aulas 6 e 8.
- **Shared Kernel (núcleo compartilhado):** dois contextos compartilham um pedaço pequeno de modelo. Poderoso, mas perigoso — qualquer mudança afeta os dois. Use com muita cautela.

Desenhar o context map mostra, **antes de uma linha de código**, onde haverá acoplamento, onde vai precisar de tradução (ACL) e onde a consistência será apenas eventual.

```
   [ Emissão ] --publica evento--> [ Gravação ] --publica evento--> [ Notificação ]
   (upstream)   Published Language   (downstream/   Open Host Service  (downstream)
                                      upstream)
                                          |
                                          +--evento--> [ Antifraude ] (downstream)
```

---

## 6. Event Storming — a ferramenta de descoberta

Tudo isso parece ótimo na teoria, mas **como** você descobre os agregados e contextos de um sistema real, com o negócio na sala? A técnica mais eficaz hoje é o **Event Storming** (criado por Alberto Brandolini): uma oficina colaborativa, com post-its coloridos numa parede (física ou virtual), onde negócio e técnicos modelam juntos o fluxo do domínio.

A convenção de cores (decore — você vai usar na aula):

| Cor | Elemento | Pergunta que responde |
|---|---|---|
| 🟧 Laranja | **Evento de domínio** (fato no passado: "Comprovante Gravado") | O que aconteceu? |
| 🟦 Azul | **Comando** (a ação que causa o evento: "Emitir Comprovante") | O que foi pedido? |
| 🟨 Amarelo | **Agregado** (onde o comando age e o evento nasce) | Quem é responsável? |
| 🟪 Lilás | **Política** ("sempre que X, então Y") | Que reação automática existe? |
| 🟩 Verde | **Read model** (a informação que alguém precisa ver para decidir) | O que preciso enxergar? |
| 🧍 Pessoa | **Ator** (quem dispara o comando) | Quem age? |
| 🟥 Rosa/vermelho | **Hotspot** (dúvida, conflito, risco) | Onde estamos incertos? |

O fluxo de uma sessão:

1. **Despeje os eventos** (laranja) na linha do tempo: tudo que "acontece" no negócio, no passado. ("Comprovante Emitido", "Comprovante Gravado", "Comprovante Consultado".)
2. **Para cada evento, ache o comando** (azul) que o causou e o **ator** que o disparou.
3. **Agrupe** comando+evento sob o **agregado** (amarelo) responsável.
4. **Marque as políticas** (lilás): "sempre que o comprovante é gravado, notificar o cliente".
5. **Marque os hotspots** (vermelho): onde o negócio discorda ou ninguém sabe a regra.
6. **Olhe os agrupamentos:** clusters de agregados que falam a mesma linguagem e mudam juntos são seus **bounded contexts** — as linhas onde a parede "respira" naturalmente.

O event storming entrega, de uma vez, a linguagem ubíqua, os agregados, as políticas (que viram mensageria) e as fronteiras de contexto. É a ponte entre "o negócio falando" e "os microsserviços desenhados".

---

## 7. Cenário 1 (completo): Comprovantes PIX — o projeto-guia

Vamos aplicar **tudo** num caso, do relato do usuário até os microsserviços. Este é o domínio que construiremos juntos nas aulas.

### Passo 0 — O relato (alto nível, como o negócio fala)

> "A gente precisa de um sistema que **comprove** os PIX que os clientes já fizeram. O PIX em si já é efetivado pelo core — a gente **não** processa o pagamento, só **emite e guarda o comprovante** e deixa o cliente **consultar** depois, inclusive a 2ª via. No pico (dia de pagamento, 13º), entra MUITA emissão de uma vez, e a consulta é ainda mais frequente — todo mundo querendo ver o comprovante. Não pode perder comprovante, e o cliente reclama quando faz o PIX e o comprovante 'demora a aparecer'."

### Passo 1 — Event storming: os eventos (🟧)

Despejando os fatos na linha do tempo:

```
Comprovante Emitido → Comprovante Aceito → Comprovante Gravado → Comprovante Consultado
                                                    ↓
                                          (Cliente Notificado)
```

### Passo 2 — Comandos (🟦) e atores (🧍)

- **Emitir Comprovante** — disparado pelo *sistema de canais* (após o PIX efetivar). Gera "Comprovante Emitido/Aceito".
- **Consultar Comprovante** — disparado pelo *cliente* (ou pelo atendimento). Gera "Comprovante Consultado".
- A **gravação** não é um comando de um ator humano — é uma **política** reagindo ao aceite (ver passo 4).

### Passo 3 — Identificando entidades, VOs e o agregado (🟨)

- **Entidade raiz: `Comprovante`** — tem identidade (`id` UUID v4), ciclo de vida (aceito → gravado).
- **Value objects** dentro dele (imutáveis, com validação própria):
  - `ChavePix` (tipo + valor, com regra de validade por tipo);
  - `Dinheiro` (valor + moeda);
  - `ContaBancaria` (agência 4 dígitos, conta 5, dígito 1 — formatos que são invariantes do VO);
  - `Documento` (CPF/CNPJ).
- **Invariante do agregado:** um comprovante só é válido com todos os campos obrigatórios consistentes (a validação que roda na emissão). O `Comprovante` é a raiz; ninguém cria um comprovante "pela metade".

### Passo 4 — Políticas (🟪)

- "**Sempre que** um comprovante é aceito, **então** gravá-lo de forma assíncrona." → vira a fila da Aula 4/5.
- "**Sempre que** um comprovante é gravado, **então** notificar o cliente / avisar antifraude / alimentar BI." → vira o tópico de eventos da Aula 6.

### Passo 5 — Hotspots (🟥)

- "O cliente faz o PIX e quer o comprovante **na hora**, mas a gravação é assíncrona." → conflito real: consistência imediata × volumetria. Resolução: na consulta, **re-tentar algumas vezes** antes de declarar 404 (a "janela" da gravação assíncrona). Esse hotspot é exatamente um requisito que você verá nas transcrições do PO.

### Passo 6 — Agrupando em bounded contexts → microsserviços

Os clusters que "mudam juntos e falam a mesma língua":

| Bounded context | Responsabilidade | Vira o microsserviço | Base própria? |
|---|---|---|---|
| **Emissão** | receber, validar, aceitar (202), publicar a gravação | `comprovante-emissor` | sim |
| **Gravação** | persistir o comprovante (idempotente), publicar o evento de gravado | `comprovante-gravador` | sim (fonte da verdade) |
| **Consulta** | ler por id, com cache e fallback | `comprovante-consulta` | usa o gravador como fonte; cache próprio |
| **Notificação** (e antifraude/BI) | reagir ao evento "comprovante gravado" | `comprovante-notificacao` | própria |

**Por que três (depois quatro) e não um só?** Porque **emitir**, **gravar** e **consultar** têm perfis radicalmente diferentes: a emissão precisa responder rápido e aceitar picos (por isso o `202` assíncrono); a gravação precisa de confiabilidade e não pode perder; a consulta tem volumetria altíssima e quer latência baixa (por isso o cache). Perfis não-funcionais diferentes → fronteiras diferentes → escala independente. Essa é a justificativa de microsserviço que sobrevive à banca.

> Repare: as **políticas** (passo 4) viraram **mensageria** (fila e tópico) e os **hotspots** viraram **requisitos de resiliência** (retries, idempotência). O DDD não só desenhou os serviços — ele já apontou onde vão estar os problemas distribuídos das próximas 7 aulas.

---

## 8. Cenário 2 (completo): Fatura de cartão de crédito

Agora um domínio diferente, para você ver o método se repetir — e é o caso-fio-condutor dos exercícios do módulo.

### Passo 0 — O relato

> "Quero gerir a **fatura** do cartão: ao longo do mês, lançamentos vão **entrando** na fatura; num dia de corte ela **fecha** com um valor total; o cliente **paga** (total ou parcial); e isso tudo precisa bater certinho — não pode a soma dos lançamentos divergir do total, e não pode entrar lançamento numa fatura já fechada."

### Passo 1–2 — Eventos e comandos

```
Lançamento Registrado → Fatura Fechada → Pagamento Recebido → Fatura Quitada
```

- **Registrar Lançamento** (ator: sistema de autorização do cartão).
- **Fechar Fatura** (ator: agendador/política de data de corte).
- **Pagar Fatura** (ator: cliente).

### Passo 3 — Entidades, VOs e agregado

- **Agregado `Fatura` (raiz).** Esta é a parte mais instrutiva: a **invariante** "soma dos lançamentos = total" e a regra "não lançar em fatura fechada" só são garantíveis se `Lancamento` for parte do agregado `Fatura` e **só puder ser alterado através dela** (veja o código do bloco 3.3). Se você fizer `Lancamento` um agregado separado num outro serviço, vai precisar de transação distribuída para manter a soma — e cair no inferno que a Aula 2 tenta evitar.
- **Entidade `Lancamento`** — tem identidade dentro da fatura, mas não vive sozinha fora dela.
- **VOs:** `Dinheiro` (reaproveitado), `Periodo` (competência da fatura), `StatusFatura` (aberta/fechada/quitada como enum-VO).

### Passo 4 — Políticas

- "Sempre que a data de corte chega, fechar a fatura." 
- "Sempre que o pagamento cobre o total, marcar como quitada."

### Passo 5–6 — Contextos

| Bounded context | Por quê separado |
|---|---|
| **Faturamento** | dono da `Fatura`, dos lançamentos e do fechamento (consistência forte interna) |
| **Pagamentos** | recebe o pagamento e confirma — outro ciclo de vida, outra linguagem ("quitação", "baixa") |
| **Cobrança/Notificação** | reage a "fatura fechada/vencida" para avisar o cliente |

Note o contraste com o cenário PIX: aqui a consistência **dentro** do agregado `Fatura` é forte (uma transação local garante a invariante da soma), enquanto **entre** Faturamento e Pagamentos a consistência é eventual (coordenada por SAGA — Aula 2). Saber **onde** a consistência é forte e onde é eventual *é* a decisão arquitetural central — e ela cai direto do desenho dos agregados e contextos.

---

## 9. Heurísticas de corte e armadilhas

**Heurísticas para traçar fronteiras:**

- Corte por **capacidade de negócio** ("emissão de comprovante"), não por camada técnica ("serviço de DAO").
- Siga as **costuras organizacionais** (Lei de Conway): times que mudam coisas por razões diferentes provavelmente são contextos diferentes.
- **Um agregado nunca atravessa fronteira de serviço.** Se a fronteira parte um agregado, ela está errada.
- **Perfis não-funcionais diferentes** (escala, latência, criticidade) justificam separar contextos que pareciam um só.

**Armadilhas clássicas:**

- **Entidade Deus** (`Cliente`/`Conta` com 80 campos de todos os contextos) — sintoma de contextos colados.
- **Base de dados compartilhada** "só no começo" — vira permanente e mata a independência. Cada serviço, sua base.
- **Microsserviço anêmico** — só CRUD remoto, sem regra própria; adiciona rede sem adicionar autonomia.
- **Cortar fino demais cedo demais** — é mais fácil dividir um serviço grande depois do que fundir cinco serviços errados. Comece com fronteiras maiores e divida quando a dor aparecer. Este processo é **iterativo**: cada aula você entende melhor o domínio e ajusta.

---

## 10. Ponte com o legado

Quem mantém mainframe já fez DDD sem o nome: módulos COBOL, *copybooks* por área, pacotes de *stored procedures* eram bounded contexts implícitos — fronteiras de modelo que existiam na cabeça das pessoas, não no deploy. O que muda hoje **não é o conceito de fronteira**, é que ela passou a ser **rede + contrato versionado**, com latência e falha parcial que não existiam dentro do mesmo CICS.

E há um ponto de ouro aqui: ao integrar o novo com o legado, você quase sempre vai precisar de uma **Anti-Corruption Layer** (seção 5). O "Cliente" e a "Conta" estranhos, com 30 anos de campos herdados, são **traduzidos** na fronteira para o seu modelo limpo — para que a bagunça do legado não vaze para dentro dos seus contextos novos. Veterano de legado que entende ACL vira o melhor arquiteto de migração da sala.

---

## 11. IA & agentes hoje

A mesma disciplina de fronteira governa **sistemas multiagentes** — e isso deixou de ser curiosidade:

- **Fronteira de agente = bounded context.** Um agente "faz-tudo", com um prompt gigante e dez responsabilidades, degrada exatamente como um monólito: contexto poluído, comportamento imprevisível, impossível de avaliar. Arquiteturas robustas separam agentes por responsabilidade, com **contratos claros** entre eles — o mesmo raciocínio de cortar microsserviços por capacidade.
- **Context engineering é o "DDD do prompt".** Decidir o que entra (e o que **não** entra) no contexto de um agente é decidir sua fronteira e sua linguagem ubíqua. Vazar contexto entre agentes é o equivalente a dois serviços compartilhando tabela: acopla e corrompe.
- **LLM como ferramenta de modelagem, não como decisor.** Usar um modelo para extrair linguagem ubíqua de transcrições de reunião, sugerir agregados ou rascunhar um event storming é um ótimo acelerador — você vai fazer exatamente isso com os artefatos de requisitos do projeto. Mas a **decisão de corte** continua sua: o modelo propõe, o arquiteto valida contra os trade-offs de consistência e escala que ele não enxerga.

---

## 12. Para ir além

- **Eric Evans**, *Domain-Driven Design: Atacando as Complexidades no Coração do Software* — a fonte (caps. de linguagem ubíqua, agregados e bounded context).
- **Vaughn Vernon**, *Implementando Domain-Driven Design* — o "como fazer", mais prático; o melhor capítulo sobre agregados.
- **Alberto Brandolini**, *Introducing EventStorming* — a técnica da seção 6, do autor.
- **Martin Fowler** — verbetes *BoundedContext*, *DomainDrivenDesign* e *UbiquitousLanguage* (martinfowler.com).
- **Microsoft Architecture Center** — *Using domain analysis to model microservices* (a ponte DDD→microsserviço, com exemplo).

> **Na próxima aula:** com os contextos desenhados e os agregados definidos, surge o problema inevitável de quem separou os dados: se cada serviço tem sua base, **quem garante que "emitir" e "gravar" não se contradigam** quando um falha e o outro não? É a entrada da consistência distribuída e do padrão **SAGA**.
