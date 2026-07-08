# Complemento — Fila × tópico, brokers e garantias: fechando o gap da Aula 5

> **Tempo de leitura:** ~14 min. Leitura pós-aula. Na Aula 5 apareceram cinco perguntas que não são dúvidas soltas — são a **mesma dúvida vista de cinco ângulos**: *o que exatamente diferencia um broker de fila de um broker de log, e o que é natureza da ferramenta versus escolha de configuração?* As respostas já estavam espalhadas pelas Aulas 5, 6 e 7; este documento as coloca **frente a frente**, que é onde o entendimento trava. Boa parte de vocês já tinha a intuição certa em produção — aqui o objetivo é dar o **nome** e o **porquê** ao que vocês já fazem.

---

## 1. A dúvida-mãe: RabbitMQ × Kafka, e por que "cada um faz uma coisa" é meia-verdade

A pergunta que mais apareceu — *"os dois servem para fila e para tópico?"* — merece uma resposta honesta, porque a versão simplificada ("fila é Rabbit, tópico é Kafka") vira uma regra rígida que quebra na primeira arquitetura real.

A resposta em uma frase: **os dois conseguem, tecnicamente, imitar o comportamento do outro — mas foram projetados a partir de modelos mentais opostos, e o preço aparece quando você força a ferramenta contra o seu design.**

Veja os dois lados da imitação:

- **RabbitMQ faz pub/sub** (comportamento de "tópico"). Você já viu como na Aula 6, §2: uma **fanout exchange** replica a mesma mensagem para várias filas. Notificação, antifraude e BI, cada um com sua fila ligada à mesma exchange, recebem cópias do mesmo fato. Isso é broadcast — o "comportamento de tópico" — feito com peças de fila.
- **Kafka faz work-queue** (comportamento de "fila"). Você verá na Aula 7, §3: dentro de um **consumer group**, cada partição é lida por **uma só** instância. Suba um grupo com várias instâncias apontando para o mesmo tópico e elas **dividem** o trabalho entre si — exatamente o "vários workers competem, um pega" da fila.

Então por que não são intercambiáveis? Porque o que os separa **não é o que cada um consegue fazer, e sim o que acontece com a mensagem depois** — e isso é decisão de projeto, não de configuração:

| Dimensão | RabbitMQ (broker de fila) | Kafka (log distribuído) |
|---|---|---|
| Depois do `ack`, a mensagem... | **some** (é removida) | **permanece** no log até a retenção expirar |
| Reprocessar o passado (replay) | **não** — já foi descartada | **sim** — reposiciona o offset e relê |
| Roteamento | **rico** — exchanges, bindings, routing keys, headers | **simples** — publica no tópico, chave decide a partição |
| Tratamento de falha por mensagem | **nativo e fino** — DLQ, retry, TTL por mensagem | **não nativo** — você constrói (tópico de retry/DLT na aplicação) |
| Ordem | FIFO na fila com 1 consumidor (Aula 6, §7) | por **partição**, via chave (Aula 7, §4) |
| Modelo mental | "distribuir **trabalho** a ser feito" | "reter **fatos** que aconteceram" |

A régua de decisão, então, **não é** "o que a ferramenta permite" — é **"para que ela foi construída"**:

> Se a frase natural é um **verbo no imperativo** — "grave o comprovante", "envie o e-mail", "processe o pagamento" — é **trabalho → fila → RabbitMQ** é a escolha idiomática. Se a frase é um **fato no passado** — "comprovante foi gravado", "pagamento foi aprovado" — é **evento → tópico → Kafka** é a escolha idiomática. Essa é, literalmente, a mesma distinção comando/evento do event storming da Aula 1 e da tabela da Aula 5, §3. O desenho do domínio já aponta o broker.

Forçar contra o design custa caro nos dois sentidos: fazer replay com RabbitMQ significa reinventar retenção que ele não tem; fazer roteamento condicional fino e DLQ por mensagem com Kafka significa reconstruir na aplicação o que o Rabbit dá de graça. Dá para fazer — mas você está pagando para lutar contra a ferramenta.

> **A frase para guardar numa banca:** "Não existe Kafka *melhor* que RabbitMQ. Existe *trabalho* e existe *fato*. A pergunta certa nunca é 'qual é mais poderoso', é 'a minha mensagem é uma ordem para alguém executar, ou um fato que muitos observam?'"

---

## 2. IBM MQ: o que ele "usa por baixo" — e por que a pergunta esconde três níveis

A pergunta *"o que o IBM MQ usa por baixo?"* tem uma premissa que vale desfazer com cuidado, porque desfazê-la esclarece de uma vez a confusão que estava por trás de várias dúvidas da aula.

**IBM MQ não usa outro broker por baixo — ele *é* o broker.** Assim como RabbitMQ e Kafka são brokers, IBM MQ (o antigo MQSeries, citado na Aula 5, §9) é um broker. Ninguém está "embaixo" dele. O que provavelmente se quis perguntar é: *qual o modelo dele (fila ou tópico) e como a aplicação fala com ele?* E aí mora o ponto que destrava tudo.

Existem **três níveis** que costumam ser fundidos num só, e separá-los resolve metade das dúvidas de mensageria:

1. **O broker** — o produto que roteia e guarda mensagens. Ex.: IBM MQ, RabbitMQ, Kafka, ActiveMQ.
2. **O protocolo de fio** (wire protocol) — como os bytes trafegam na rede entre app e broker. Ex.: **AMQP** (RabbitMQ), **protocolo próprio do Kafka**, **MQI** (o protocolo nativo do IBM MQ).
3. **A API/spec da aplicação** — a interface que o seu código Java chama. Ex.: **JMS** (Java Message Service).

Com esses três níveis à mão, o IBM MQ se descreve sem mistério:

- **Modelo:** IBM MQ é, na origem e no coração, um sistema de **filas** — *message queuing*, o nome diz. Ele **também** faz publish/subscribe via tópicos administrados, mas a fila é a estrutura primária.
- **Protocolo nativo:** **MQI** (Message Queue Interface), o protocolo próprio da IBM.
- **API:** historicamente acessado por **JMS** no mundo Java — foi exatamente isso que a Aula 5, §9 mencionou ("IBM MQ com JMS").

E aqui está a chave que dissolve a confusão de toda a turma:

> **JMS não é um broker. JMS é uma *especificação de API* — uma interface Java padrão para falar com *qualquer* broker de mensagens.** RabbitMQ, IBM MQ e ActiveMQ podem todos ser acessados via JMS. AMQP, por outro lado, é um *protocolo de fio*, num nível totalmente diferente. Dizer "usei JMS" descreve **como o seu código chama**, não **qual broker** nem **qual protocolo** por baixo.

O paralelo que fixa: **JMS está para brokers de mensagem assim como JDBC está para bancos de dados.** JDBC é a API Java; PostgreSQL/Oracle/MySQL são os bancos; o protocolo de rede de cada um é um terceiro nível. Você troca de banco sem trocar de JDBC — e troca de broker sem trocar de JMS. Quando alguém pergunta "o que o IBM MQ usa por baixo", a resposta completa é: *ele é o broker; o protocolo nativo é MQI; e a sua aplicação Java normalmente fala com ele via JMS.* Três níveis, três respostas.

---

## 3. "Fila não é seguro, perde mensagem" — o acerto certo pelo motivo errado

Na aula, alguém contou que num sistema bancário a equipe escolheu Kafka **porque fila não era segura e podia perder mensagem**. Vale parar aqui, porque a decisão final pode ter sido ótima — mas o *motivo* declarado, se levado adiante, vai contaminar decisões futuras. E o objetivo deste documento é justamente separar natureza da ferramenta de artefato de configuração.

**A afirmação que precisa ser refinada: uma fila não perde mensagem *por ser fila*.** Perda de mensagem quase sempre é **configuração**, não natureza. O RabbitMQ entrega a mesma garantia at-least-once do Kafka (Aula 5, §4) quando você liga as quatro peças certas:

- **Fila durável** (`durable`) — sobrevive a restart do broker.
- **Mensagem persistente** (`persistent`) — é gravada em disco, não só em memória.
- **Publisher confirms** — o broker confirma ao produtor que **recebeu e persistiu**; sem confirm, o produtor reenvia.
- **Ack manual no consumidor** (Aula 6, §3) — o broker só descarta depois do sucesso comprovado.

Se faltar qualquer uma — fila não-durável, mensagem transiente, auto-ack, ou produtor que dispara sem esperar confirm — aí **sim** a mensagem some. Mas some por **como foi configurada**, não porque "fila é insegura". Uma equipe que perdeu mensagem no Rabbit muito provavelmente tinha auto-ack ligado ou publicava sem confirms. Trocar de broker "consertou" o sintoma sem nomear a causa — e a causa viaja junto para o próximo projeto.

**Então por que o banco escolheu Kafka de verdade — e provavelmente escolheu bem?** Porque num contexto bancário os motivos *legítimos* costumam ser outros, e são exatamente as capacidades que a fila **realmente** não tem (Aula 7, §5–6):

- **Retenção e replay:** o log guarda o evento depois de consumido. Reprocessar os últimos 3 dias após corrigir um bug, ou reconstruir uma projeção do zero, é nativo — e numa fila é impossível, o passado já foi descartado.
- **Trilha de auditoria / event sourcing:** a sequência de fatos vira a fonte da verdade auditável ("por que este estado?") — requisito comum em finanças e compliance.
- **Fan-out para muitos consumidores independentes** relendo o mesmo histórico, cada um no seu offset.
- **Throughput de escrita sequencial** em volumes muito altos.

> **O reenquadramento honesto:** a escolha do Kafka num banco costuma estar **certa** — mas por causa de **replay, retenção e auditoria**, não por "fila perde mensagem". Guardar o motivo certo importa: se você levar adiante a crença "fila é insegura", vai descartar RabbitMQ em casos onde ele seria a ferramenta idiomática e mais simples (um comando ponto-a-ponto com DLQ). A garantia de entrega você *configura* nos dois; o replay só um deles *tem*.

---

## 4. Duas filas de retorno (sucesso + erro): você reinventou dois padrões com nome

Outro relato da aula: *"uso uma solução com duas filas de retorno — uma para sucesso, outra para erro."* Isso não é gambiarra — é intuição de engenheiro que **reinventou padrões catalogados** sem saber o nome deles. Vale dar o nome, porque a versão nomeada vem com ferramental que a artesanal não tem.

### A fila de erro = uma DLQ artesanal

A sua "fila de erro" é, na prática, uma **Dead Letter Queue** construída à mão (Aula 6, §6). O conceito é o mesmo — não descartar o que falhou, separar para análise. O que você **ganha** adotando a DLQ nativa em vez da versão manual:

- **Roteamento automático** via `x-dead-letter-exchange`: a mensagem vai para a DLQ sozinha no `nack` com `requeue=false` ou no retry esgotado — você não precisa publicar na fila de erro manualmente no `catch`.
- **Headers de diagnóstico `x-death`:** quantas vezes morreu, de qual fila, por quê — a "sala de necropsia" da Aula 6, §6, de graça.
- **Distinção transitório × permanente** (Aula 6, §1): a versão manual costuma jogar *tudo* que deu erro na fila de erro, misturando "banco oscilou" (que retry resolveria) com "mensagem malformada" (que nunca resolve). A topologia nativa separa: transitório vai para **retry com backoff**, permanente vai **direto** para a DLQ.

Ou seja: sua fila de erro captura o caso permanente — mas provavelmente está capturando também transitórios que um retry teria salvo, e sem os metadados de investigação.

### A fila de sucesso = depende de qual dos dois padrões você tem

A fila de sucesso é mais interessante, porque pode ser **uma de duas coisas** bem diferentes — e saber qual muda a arquitetura:

- **Se o produtor original fica *esperando* essa resposta** para prosseguir (correlaciona a resposta com a request que enviou, por um id de correlação), você tem um **Request-Reply pattern** — RPC construído sobre filas. É legítimo, mas é meio síncrono disfarçado: cuidado com o alerta da Aula 5, §8 ("quando **não** usar assíncrono") — se o cliente precisa do resultado para o próximo passo, um round-trip de fila pode estar só recriando uma chamada síncrona com passos a mais.
- **Se ninguém fica esperando e a "fila de sucesso" apenas *anuncia* que algo foi concluído** para quem se interessar, você tem um **evento de domínio** — o `ComprovanteGravadoEvent` da Aula 7. E se há **vários** interessados nesse sucesso (ou virão a existir), esse é precisamente o caso de **tópico/pub-sub**, não de mais uma fila ponto-a-ponto.

> **A pergunta que distingue os dois:** *alguém fica bloqueado esperando a mensagem de sucesso chegar?* Se **sim** → é Request-Reply (e reveja se não deveria ser síncrono mesmo). Se **não**, e há um ou mais interessados no fato → é um **evento**, e o lugar dele pode ser um tópico. Você pode estar fazendo event-driven há anos sem chamar pelo nome — ou reinventando RPC sobre fila. O nome revela qual.

---

## 5. Tabela-síntese: o vocabulário alinhado

Para consolidar, o mapa dos termos que se cruzaram na discussão — separados pelos níveis que a §2 introduziu, porque misturá-los é a origem da maioria das confusões:

| Termo | O que é (nível) | Exemplo |
|---|---|---|
| **Broker** | O produto que roteia e guarda mensagens | RabbitMQ, Kafka, IBM MQ |
| **Fila (queue)** | Estrutura ponto-a-ponto: um trabalho, um consumidor pega | `comprovante.gravar.q` |
| **Tópico (topic)** | Log de fatos: muitos consumidores independentes leem | `comprovante-gravado` |
| **AMQP** | Protocolo de fio | Falado pelo RabbitMQ |
| **MQI** | Protocolo de fio nativo | Falado pelo IBM MQ |
| **JMS** | API/spec Java para falar com brokers | Acessa IBM MQ, RabbitMQ, ActiveMQ |
| **DLQ** | Fila lateral para mensagens que falharam | `comprovante.gravar.dlq` (Aula 6) |
| **Request-Reply** | Padrão de resposta correlacionada sobre fila | "fila de sucesso" que alguém aguarda |
| **Evento de domínio** | Fato publicado, muitos observam | `ComprovanteGravadoEvent` (Aula 7) |
| **at-least-once** | Garantia: nunca perde, pode duplicar | Padrão prático (Aula 5, §4) |
| **Idempotência** | Reprocessar a mesma msg não duplica efeito | `existsById` + constraint (Aula 5, §5) |

---

## 6. Três frases para levar para a prova (e para a vida)

1. **"A mensagem é trabalho ou fato?"** — decide fila×tópico antes de decidir Rabbit×Kafka. A ferramenta vem depois da semântica, nunca antes.
2. **"Perda de mensagem é configuração, não natureza."** — durável + persistente + confirms + ack manual dá at-least-once em qualquer broker sério. Kafka se escolhe por *replay e auditoria*, não por "fila é insegura".
3. **"JMS é API, AMQP é protocolo, o broker é o broker."** — três níveis distintos. Quem os separa nunca mais se confunde sobre "o que o IBM MQ usa por baixo".

---

## 7. Para ir além

- **Gregor Hohpe & Bobby Woolf**, *Enterprise Integration Patterns* — *Request-Reply*, *Dead Letter Channel*, *Publish-Subscribe Channel*: os nomes formais dos padrões que vocês já usam.
- **Documentação RabbitMQ** — *Publisher Confirms* e *Consumer Acknowledgements*: a prova de que a fila não perde quando configurada certo.
- **Documentação Apache Kafka** — *Topics, Partitions, Consumer Groups*: o modelo de log que dá o replay.
- **IBM MQ documentation** — *Queues, Topics, and the MQI*; e a especificação **JMS (Jakarta Messaging)** para ver a API que abstrai todos eles.
- **Martin Kleppmann**, *Designing Data-Intensive Applications* (caps. de logs e streams) — por que o log retido muda o que é possível.

> **De volta à Aula 6:** com o vocabulário alinhado, a topologia de exchange/binding/DLQ que vocês montaram deixa de parecer "config do Rabbit" e passa a se ler como o que é — a materialização, em infraestrutura, das mesmas decisões de domínio (comando×evento, transitório×permanente) que vocês vêm tomando desde a Aula 1.
