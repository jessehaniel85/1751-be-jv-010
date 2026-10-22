# Material do Aluno — Aula 2: Consistência distribuída e SAGA

> **Tempo de leitura:** ~13 min. Esta é a aula em que o conforto do `COMMIT` único acaba. Na Aula 1 você cortou o sistema em serviços com bases próprias; o preço dessa independência aparece agora: um fluxo de negócio que antes cabia numa transação ACID passa a cruzar processos, redes e bancos diferentes. Manter o dado correto quando *parte* da operação falha deixa de ser garantia do banco e vira **responsabilidade sua, no código**. Leia com atenção aos trade-offs — em domínio bancário, escolher errado entre "forte" e "eventual" não é detalhe técnico: é risco regulatório.

---

## 1. O problema que surge quando você separa os dados

No monólito, "emitir o comprovante e gravá-lo" eram duas escritas dentro da **mesma** transação. O banco te dava a garantia mais valiosa que existe: **atomicidade** — ou as duas acontecem, ou nenhuma. Se a gravação falha, o `ROLLBACK` apaga a emissão, e o mundo nunca soube que algo deu errado.

Distribua os dados em bases segregadas (a decisão da Aula 1) e essa garantia **evapora**. No nosso fluxo PIX, a emissão respondeu `202 Accepted` e colocou um comando na fila; o gravador, do outro lado, falhou ao persistir. Agora existe um comprovante que o cliente acredita ter — afinal recebeu o `202` — mas que **não existe no banco**. Em um sistema de varejo isso é um carrinho abandonado; em um banco, é um comprovante de pagamento que sumiu, e isso tem nome: inconsistência com impacto regulatório e financeiro.

O ponto central desta aula: **você não escolhe se haverá falha parcial** num sistema distribuído — a rede cai, o nó reinicia, o timeout dispara. Você só escolhe se vai **tratá-la de propósito** ou descobri-la em produção, num domingo, com o cliente no telefone.

---

## 2. Por que o 2PC saiu de cena

A primeira reação de quem vem do mundo transacional é: "então me devolvam o `COMMIT` único, distribuído". Esse é o **two-phase commit (2PC)** — um protocolo onde um *coordenador* pergunta a todos os participantes "vocês conseguem commitar?" (fase 1, *prepare*) e, se todos disserem sim, manda "commitem" (fase 2, *commit*). No papel, resolve. Na prática, foi praticamente abandonado em arquiteturas de alta disponibilidade, por três razões:

- **Bloqueio de recursos.** Entre o *prepare* e o *commit*, cada participante segura *locks* nos dados envolvidos. Sob carga, isso vira contenção: linhas travadas, throughput despencando exatamente no pico (o nosso dia de pagamento).
- **Disponibilidade refém do coordenador.** Se o coordenador cai depois do *prepare* e antes do *commit*, os participantes ficam **bloqueados** sem saber o que fazer — segurando *locks*, esperando uma ordem que não vem. Um único ponto de falha derruba o conjunto.
- **Não escala e não combina com a nuvem.** 2PC pressupõe participantes confiáveis e baixa latência. Numa malha de microsserviços, com rede instável e serviços que vão e voltam, o protocolo passa mais tempo recuperando do que progredindo.

A troca que o mercado fez foi consciente: abrir mão da **consistência forte imediata** em favor da **consistência eventual com compensação**. E aqui vem o ponto que separa o sênior do júnior — essa é uma **decisão de negócio**, não só técnica.

### Consistência forte × eventual — o custo ao negócio

| Dimensão | Consistência forte | Consistência eventual |
|---|---|---|
| Garantia | Todos veem o mesmo estado imediatamente | Estados convergem após uma janela de tempo |
| Disponibilidade | Menor (precisa coordenar/bloquear) | Maior (cada serviço progride sozinho) |
| Latência | Alta (espera todos) | Baixa (responde já, concilia depois) |
| Custo ao negócio | Lentidão, indisponibilidade no pico | Janela em que o dado pode estar "atrasado" |
| Quando usar | Saldo de conta, débito/crédito de valor | Comprovante "aparecendo", notificação, BI |

A pergunta certa nunca é "forte ou eventual?" em abstrato. É: **"por quanto tempo, e sob quais regras, uma inconsistência temporária é tolerável para este dado específico?"**. Para o saldo da conta, a resposta é "zero" — consistência forte, transação local. Para o comprovante "aparecer" na consulta, a resposta é "alguns segundos são aceitáveis, desde que ele nunca se perca" — consistência eventual, com a rede de segurança que veremos.

---

## 3. SAGA: a transação que sabe se desfazer

Uma **SAGA** é uma sequência de **transações locais**, cada uma num serviço/banco diferente, onde cada passo tem um **passo compensatório** associado. Se um passo falha, a SAGA executa as compensações dos passos **já concluídos**, na ordem inversa, para levar o sistema de volta a um estado consistente.

O ponto que mais surpreende quem vem do ACID: **não há rollback automático**. O banco não desfaz nada por você — afinal, os passos anteriores já commitaram em seus próprios bancos. Você **programa o "desfazer"** à mão. Estornar não é apagar; é uma nova operação de negócio que neutraliza a anterior.

Existem dois jeitos de coordenar uma SAGA.

### 3.1. Orquestração — um maestro central

Um **orquestrador** conhece o fluxo inteiro e comanda cada passo, chamando os serviços e decidindo, a cada resposta, se avança ou se compensa.

```
        ┌─────────────────────────────┐
        │        ORQUESTRADOR         │
        └──────┬──────────────┬───────┘
               │              │
       (1) registrar   (2) gravar
               │              │
               ▼              ▼
        ┌──────────┐   ┌──────────────┐
        │ Emissão  │   │  Gravação    │
        └──────────┘   └──────────────┘
               ▲              │ (falhou)
               │              ▼
        (3) compensar:  ✗ erro propaga
            invalidar(id)     de volta ao maestro
```

**Vantagens:** o fluxo está num lugar só — fácil de ler, depurar, auditar e testar. Você consegue desenhar a máquina de estados.
**Custo:** o orquestrador concentra conhecimento e vira um ponto focal (não necessariamente um ponto único de falha, mas um componente que precisa de cuidado).

### 3.2. Coreografia — sem maestro, por eventos

Na **coreografia**, ninguém comanda. Cada serviço **reage** a um evento e **emite** o seu. A emissão publica `ComprovanteAceito`; o gravador escuta, grava e publica `ComprovanteGravado`; se falha, publica `GravacaoFalhou`, e a emissão escuta esse evento para se compensar.

**Vantagens:** máximo desacoplamento e resiliência — não há componente central; adicionar um novo reator (antifraude, BI) é só assinar o evento.
**Custo:** o fluxo fica **espalhado** entre os serviços. Não existe um lugar para "ler a saga inteira"; entender, depurar e testar exige reconstruir mentalmente a cadeia de eventos. Em fluxos com muitos passos, vira um *spaghetti* de reações.

| Critério | Orquestração | Coreografia |
|---|---|---|
| Onde mora o fluxo | Centralizado (maestro) | Distribuído (nos eventos) |
| Observabilidade | Alta | Baixa (precisa correlacionar) |
| Acoplamento | Maior (todos conhecem o maestro) | Menor (só conhecem eventos) |
| Resiliência a falha de componente | Maestro é crítico | Sem ponto focal |
| Quando preferir | Fluxos com lógica/decisão complexa | Fluxos lineares, muitos reatores |

Heurística prática: **comece orquestrado** (mais fácil de entender e provar correto) e migre passos para coreografia conforme o desacoplamento justifique o custo de observabilidade.

---

## 4. Ações compensatórias — você programa o "desfazer"

A compensação é onde a maioria erra. Três princípios que você precisa internalizar:

- **Compensar é uma operação de negócio, não um `DELETE`.** "Invalidar o comprovante" pode significar marcar status `INVALIDADO` e registrar o motivo — não sumir com a linha. Em domínio bancário, você raramente apaga; você **estorna**, deixando rastro.
- **Toda compensação precisa ser idempotente.** A própria compensação pode ser reexecutada (a SAGA reprocessa). Se "estornar" rodar duas vezes e estornar em dobro, você trocou um bug por outro pior.
- **Há passos que não têm compensação trivial.** Já notifiquei o cliente por SMS — não dá para "des-enviar". Por isso ordene a SAGA colocando os passos **irreversíveis o mais tarde possível**: faça tudo que é compensável primeiro e o efeito definitivo por último, quando o sucesso já é quase certo.

```java
// Orquestração com compensação explícita — Spring Boot 3 / Java 21
@Service
public class EmissaoSagaOrchestrator {

    private final EmissaoService emissao;
    private final GravadorClient gravador;

    public EmissaoSagaOrchestrator(EmissaoService emissao, GravadorClient gravador) {
        this.emissao = emissao;
        this.gravador = gravador;
    }

    public ComprovanteAceito emitir(ComprovanteRequest req) {
        // Passo 1: transação local na base de Emissão
        UUID id = emissao.registrar(req);
        try {
            // Passo 2: transação local na base de Gravação (idempotente, ver §5)
            gravador.gravar(new GravarComprovanteCommand(id, req));
            emissao.gravado(id);
            return new ComprovanteAceito(id, LocalDateTime.now());
        } catch (GravacaoException falha) {
            // Compensação do passo 1 — operação de negócio idempotente, não um DELETE
            emissao.invalidar(id, "gravacao falhou: " + falha.getMessage());
            throw new SagaAbortada(id, falha);
        }
    }
}
```

---

## 5. Idempotência: a pré-condição de tudo

Em sistema distribuído, **você sempre reprocessa**: o cliente HTTP deu *retry* por timeout; o broker fez *redelivery* da mensagem; o operador reexecutou um *replay*. Se reprocessar **duplica o efeito**, a SAGA mente — grava dois comprovantes, estorna duas vezes, notifica em dobro.

**Idempotência** é a propriedade: executar a operação uma ou N vezes produz o **mesmo** efeito observável. A ferramenta é a **chave de idempotência** — um identificador estável do *intent* da operação (no PIX, o `identificadorComprovante`/`end2end`, não um UUID gerado a cada tentativa). A regra mental: *"se eu já fiz isto com esta chave, não faço de novo; devolvo o resultado anterior"*.

Como implementar no consumidor da fila de gravação:

```java
@Service
public class GravadorService {

    private final ComprovanteRepository repo;

    public GravadorService(ComprovanteRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void gravar(GravarComprovanteCommand cmd) {
        // A chave de idempotência é o próprio identificador de negócio do comprovante.
        // Se já existe, a operação é um no-op silencioso — reprocessar é seguro.
        if (repo.existsById(cmd.identificadorComprovante())) {
            return; // já gravado numa entrega anterior; nada a fazer
        }
        Comprovante comprovante = Comprovante.aPartirDe(cmd);
        comprovante.marcarGravado();
        repo.save(comprovante);
    }
}
```

Detalhe sênior: a verificação `existsBy...` + `save` precisa ser **atômica** contra concorrência (duas entregas simultâneas da mesma mensagem). Na prática, apoie a idempotência numa **restrição de unicidade no banco** (no gabarito, a chave primária `id` do comprovante, que é o próprio `identificadorComprovante`) e trate a violação como sucesso — o banco vira o árbitro, não o seu `if`.

---

## 6. Outbox pattern: gravar *e* publicar sem mentir

Aqui está o bug mais sutil — e mais comum — de mensageria. O passo "gravar o comprovante no banco **e** publicar o evento `ComprovanteGravado`" envolve **dois recursos** (o banco e o broker) que **não compartilham transação**. Quatro coisas podem acontecer, e duas são desastre:

1. Grava e publica — ok.
2. Falha em tudo — ok (nada aconteceu).
3. **Grava, mas falha ao publicar** — o comprovante existe, mas a notificação/antifraude/BI nunca souberam.
4. **Publica, mas o commit falha** — o mundo reagiu a um comprovante que não existe.

O **outbox pattern** elimina os casos 3 e 4 com uma ideia simples: **não publique direto**. Grave o evento numa tabela `outbox` **na mesma transação** do dado. Um processo separado lê a outbox e publica. Assim, o evento só existe se o dado existiu — eles commitam ou falham **juntos**.

```
   ┌─────────── MESMA TRANSAÇÃO LOCAL ───────────┐
   │                                             │
   │   INSERT comprovante  +  INSERT outbox      │
   │   (o dado)               (o evento pendente)│
   └─────────────────────┬───────────────────────┘
                         │ commit atômico
                         ▼
              ┌──────────────────────┐
              │   tabela OUTBOX      │
              │  [evento PENDENTE]   │
              └──────────┬───────────┘
                         │ lê (polling/CDC)
                         ▼
              ┌──────────────────────┐
              │  Relay / Publisher   │ ──publica──► [ Broker ]
              └──────────────────────┘   marca PUBLICADO
```

```java
@Transactional
public void gravar(GravarComprovanteCommand cmd) {
    Comprovante c = repo.save(Comprovante.aPartirDe(cmd));
    // Mesmo @Transactional: o evento é persistido junto com o dado.
    outbox.save(OutboxEvent.de("ComprovanteGravado", c.id(), c.snapshot()));
    // NÃO publicamos aqui. Um relay lê a outbox e publica depois.
}
```

Guarde este nome: **outbox** resolve metade dos bugs de mensageria que você verá nas Aulas 4–6. E note como ele se encaixa com a idempotência da §5 — o relay publica *at-least-once* (pode publicar a mesma linha duas vezes se cair entre publicar e marcar como `PUBLICADO`), e o consumidor idempotente absorve a duplicata.

---

## 7. O mito do exactly-once

Times inteiros perdem semanas buscando "entrega exactly-once" do broker. A verdade desconfortável: **exactly-once de ponta a ponta, na entrega, não existe** num sistema distribuído com falhas. O broker te oferece, na prática, uma de duas garantias:

- **at-most-once:** entrega no máximo uma vez — pode **perder** mensagens (inaceitável para comprovante).
- **at-least-once:** entrega pelo menos uma vez — pode **duplicar** (e isso você consegue tratar).

O que parece "exactly-once" para o negócio é, na verdade, a soma que você já montou:

> **exactly-once efetivo = at-least-once (broker) + idempotência (consumidor)**

Ou seja: aceite as duplicatas na entrega e torne o **efeito** único pela chave de idempotência. Parar de caçar a garantia mágica do broker e investir em consumidores idempotentes é uma das marcas de maturidade arquitetural.

---

## 8. Exemplo trabalhado: a emissão→gravação do PIX como SAGA

Vamos juntar tudo no projeto-guia.

**O fluxo feliz.** O canal chama `POST /comprovantes`. A emissão valida, registra com status `ACEITO` (transação local na base de Emissão) e responde `202`. Em vez de chamar o gravador direto, ela grava na **outbox** o comando `GravarComprovante` na mesma transação. O relay publica; o gravador consome, checa a **chave de idempotência**, persiste com status `GRAVADO` (transação local na base de Gravação) e, na mesma transação, escreve um `ComprovanteGravado` na *sua* outbox — que alimentará notificação/antifraude/BI (Aula 6).

**A falha que vira compensação.** Suponha que a gravação rejeite o comprovante por uma regra de negócio definitiva (ex.: dados inconsistentes que a emissão não pegou). O gravador publica `GravacaoRejeitada`. A emissão consome esse evento e executa a **compensação**: marca o comprovante como `INVALIDADO`, com motivo registrado. Quando o cliente consultar (Aula 3), receberá o estado correto — não um comprovante fantasma.

**Por que isso é SAGA e não transação.** Cada serviço commitou no seu banco. Não houve `ROLLBACK` global — houve uma **segunda operação de negócio** (invalidar) compensando a primeira (registrar). A consistência é **eventual**: por uma janela de segundos, o comprovante esteve `ACEITO` antes de ser `GRAVADO` ou `INVALIDADO`. E essa janela é exatamente o que o negócio aceitou na §2.

### O caso fatura, em paralelo

O mesmo método vale para a fatura (cenário 2 da Aula 1). "Fechar a fatura" e "registrar a cobrança no serviço de Pagamentos" são serviços diferentes. SAGA: passo 1 fecha a fatura (transação local em Faturamento); passo 2 abre a cobrança (transação local em Pagamentos). Se o passo 2 falha, a compensação **reabre** a fatura (não a "deleta") — uma operação de negócio que estorna o fechamento. Note o contraste: a invariante "soma dos lançamentos = total" continua **forte** *dentro* do agregado `Fatura`; só a coordenação *entre* Faturamento e Pagamentos é eventual.

---

## 9. Armadilhas comuns

- **Esquecer a compensação.** O "vai dar certo" é o caminho mais curto para descobrir o estado inconsistente em produção. Desenhe a compensação **antes** do caminho feliz.
- **Compensação não-idempotente.** O "desfazer" roda duas vezes (porque a SAGA reprocessa) e estraga em dobro. Toda compensação é idempotente, ponto.
- **Tratar consistência eventual como detalhe técnico.** A janela tolerável é uma decisão **do negócio**; alinhe e registre. Em banco, "alguns segundos" pode ser ok para "o comprovante aparecer" e inaceitável para "o saldo bater".
- **Ordenar mal os passos irreversíveis.** Enviar o SMS antes de confirmar a gravação te deixa sem compensação possível. Irreversível por último.
- **Confiar no exactly-once do broker.** Você vai duplicar; planeje o consumidor idempotente desde o dia 1.

---

## 10. Ponte com o legado Caixa

Quem operou mainframe já fez SAGA sem o nome. O **job control** clássico — passo 1 roda; se der erro, um *job* de reversão desfaz; tudo encadeado por JCL — **já era uma SAGA orquestrada**, com o escalonador no papel de maestro. A diferença não é o conceito: é que naquele mundo a compensação era um *job* ad-hoc, escrito caso a caso, sem o vocabulário de "ação compensatória" nem a disciplina de idempotência explícita.

O mesmo vale para as *stored procedures* que coordenavam várias atualizações: eram orquestradores embutidos no banco. Trazer os nomes — SAGA, compensação, idempotência, outbox — não inventa nada novo para o veterano; **torna explícito e testável** o que ele já fazia na unha, e o prepara para o mundo onde os passos estão em rede, não no mesmo CICS.

---

## 11. IA & agentes hoje

A SAGA reaparece, quase idêntica, no mundo de agentes:

- **Agentic workflow é uma SAGA.** Um pipeline `planejar → executar → revisar` precisa de **passos compensatórios** quando um agente erra ou "alucina". Se o agente já criou um recurso (abriu um ticket, enviou um e-mail, escreveu num banco) e o passo seguinte falha, alguém precisa **desfazer** — e, como em qualquer SAGA, não há rollback automático: você programa a compensação.
- **Orquestração × coreografia em multiagente** é o **mesmo trade-off** desta aula. Um orquestrador central (um agente "gerente" que chama os demais) dá controle e auditoria; agentes reagindo a eventos/mensagens dão desacoplamento, ao custo de observabilidade — você não consegue "ler o fluxo inteiro" num lugar só.
- **Idempotência importa *mais* com IA.** Reexecutar um passo **não-determinístico** pode produzir um resultado **diferente** *e* repetir o efeito colateral. Controle pela **chave de tarefa** (idempotência por id da tarefa) e **cacheie a saída** do passo, para que o *retry* devolva o resultado anterior em vez de chamar o modelo de novo — barato, estável e sem efeito duplicado.

---

## 12. Para ir além

- **Chris Richardson**, *Microservices Patterns* — os capítulos de **Saga** e **Transactional Outbox** são a referência canônica.
- **Pat Helland**, *Life Beyond Distributed Transactions: an Apostate's Opinion* — o paper que explica por que abandonamos o 2PC.
- **Hohpe & Woolf**, *Enterprise Integration Patterns* — o vocabulário de mensageria que sustenta outbox e coreografia.
- **Martin Fowler** — verbetes *Saga*, *Idempotency* e o conceito de *eventual consistency* (martinfowler.com).

> **Na próxima aula:** a SAGA garante que o comprovante *fica* correto — mas a **consulta** dele vai bater no banco a cada chamada, e no pico isso é milhões de leituras/dia martelando a fonte da verdade. Entra o **cache com Redis**: como aliviar o banco sem servir dado velho, e por que "guardar tudo" é o caminho mais curto para o bug mais difícil de reproduzir.
