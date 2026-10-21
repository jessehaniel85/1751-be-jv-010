#!/usr/bin/env bash
# Demonstração final do projeto guiado PIX (gabarito aula-08) rodando sobre os clusters
# de RabbitMQ (3 nós) e Kafka (3 brokers). Para o computador pessoal, com Docker
# (macOS, Linux ou Windows com WSL 2). Roteiro: plano-de-aulas/aula-07-complemento-demo-final.md
#
#   ./demo.sh preparar          # confere Java 21 e Docker, compila o gabarito (sem testes)
#   ./demo.sh up                # sobe os dois clusters, prepara fila quorum e tópico, sobe os 4 serviços
#   ./demo.sh status            # contêineres, saúde dos serviços, filas e tópico (quem é líder)
#   ./demo.sh derruba rabbit 1  # para um nó (rabbit 1..3 | kafka 1..3)
#   ./demo.sh levanta rabbit 1  # religa o nó
#   ./demo.sh logs gravador     # acompanha o log de um serviço (emissor|gravador|consulta|notificacao)
#   ./demo.sh down              # para os serviços e derruba os clusters, apagando os dados
#
# Também: clusters-up | clusters-down | apps-up | apps-down | lideres
#
# Os docker-compose dos clusters ficam em ambiente/clusters/, na raiz do repositório.
# Java: usa JAVA21_HOME, senão JAVA_HOME, senão o java do PATH; precisa ser o 21 (o 25 quebra o gabarito).
#   JAVA21_HOME=/caminho/do/jdk-21 ./demo.sh preparar

set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GABARITO="$(cd "$DEMO_DIR/../aula-08" && pwd)"
CLUSTERS_DIR="${CLUSTERS_DIR:-$(cd "$DEMO_DIR/../../../../ambiente/clusters" && pwd)}"
JAVA21_HOME="${JAVA21_HOME:-${JAVA_HOME:-$(dirname "$(dirname "$(command -v java || echo /nao/achei/bin/java)")")}}"
RUN_DIR="$DEMO_DIR/.run"

SERVICOS=(gravador emissor consulta notificacao)
porta() {
    case "$1" in
        emissor) echo 8081 ;; gravador) echo 8082 ;; consulta) echo 8083 ;; notificacao) echo 8084 ;;
    esac
}

# Os serviços rodam no host e enxergam os nós pelas portas publicadas no compose.
export SPRING_RABBITMQ_ADDRESSES="localhost:5672,localhost:5673,localhost:5674"
export KAFKA="localhost:19092,localhost:29092,localhost:39092"

# Só o "status" geral do actuator, sem os detalhes.
saude() { curl -s "localhost:$(porta "$1")/actuator/health" | grep -oE '^\{"status":"[A-Z]+"' | cut -d'"' -f4 || true; }

passo() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
falha() { printf 'ERRO: %s\n' "$*" >&2; exit 1; }

rabbitctl() { docker compose -f "$CLUSTERS_DIR/rabbitmq/docker-compose.yml" exec -T "${RABBIT_NO:-rabbit-1}" rabbitmqctl -q "$@"; }
kafka_topics() {
    docker compose -f "$CLUSTERS_DIR/kafka/docker-compose.yml" exec -T "${KAFKA_NO:-kafka-1}" \
        /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 "$@"
}

# Primeiro nó de pé de um cluster, para os comandos de inspeção não dependerem do nó 1.
no_vivo() {
    local cluster="$1" prefixo="$2" i
    for i in 1 2 3; do
        if docker compose -f "$CLUSTERS_DIR/$cluster/docker-compose.yml" ps --status running --services 2>/dev/null \
            | grep -qx "$prefixo-$i"; then
            echo "$prefixo-$i"; return
        fi
    done
}

preparar() {
    passo "Conferindo Java 21, Maven e Docker"
    [[ -x "$JAVA21_HOME/bin/java" ]] || falha "Java não encontrado em $JAVA21_HOME (defina JAVA21_HOME com o JDK 21)"
    "$JAVA21_HOME/bin/java" -version 2>&1 | head -1
    "$JAVA21_HOME/bin/java" -version 2>&1 | head -1 | grep -q '"21' \
        || falha "o gabarito precisa do JDK 21; defina JAVA21_HOME=/caminho/do/jdk-21"
    command -v mvn >/dev/null || falha "Maven não encontrado no PATH"
    docker info >/dev/null 2>&1 || falha "Docker não está rodando (abra o Docker Desktop)"
    [[ -d "$CLUSTERS_DIR/rabbitmq" && -d "$CLUSTERS_DIR/kafka" ]] || falha "clusters não encontrados em $CLUSTERS_DIR"
    passo "Compilando o gabarito aula-08 (sem testes)"
    (cd "$GABARITO" && JAVA_HOME="$JAVA21_HOME" PATH="$JAVA21_HOME/bin:$PATH" mvn -B -q -DskipTests install)
    echo "ok"
}

clusters_up() {
    docker info >/dev/null 2>&1 || falha "Docker não está rodando (abra o Docker Desktop)"
    if docker ps --format '{{.Ports}}' | grep -qE ':(5672|15672|9092)->'; then
        falha "porta 5672/15672/9092 ocupada (gabarito-pix de pé? rode: docker compose -p gabarito-pix down)"
    fi
    passo "Subindo o cluster RabbitMQ (3 nós)"
    docker compose -f "$CLUSTERS_DIR/rabbitmq/docker-compose.yml" up -d --wait
    # Sem mudar o código: toda fila declarada sem tipo nasce quorum (3 cópias, Raft).
    rabbitctl update_vhost_metadata / --default-queue-type quorum
    echo "vhost / com default-queue-type=quorum"

    passo "Subindo o cluster Kafka (3 brokers, KRaft)"
    docker compose -f "$CLUSTERS_DIR/kafka/docker-compose.yml" up -d --wait
    # O gabarito declara o tópico com replicas(1); criado antes, ele já nasce com 3 cópias
    # e o gravador só confirma que existe.
    kafka_topics --create --if-not-exists --topic comprovante-gravado --partitions 3 --replication-factor 3
    kafka_topics --describe --topic comprovante-gravado | grep -v '^$'
}

clusters_down() {
    passo "Derrubando os clusters e apagando os dados"
    docker compose -f "$CLUSTERS_DIR/kafka/docker-compose.yml" down -v
    docker compose -f "$CLUSTERS_DIR/rabbitmq/docker-compose.yml" down -v
}

apps_up() {
    mkdir -p "$RUN_DIR"
    local s jar extra
    for s in "${SERVICOS[@]}"; do
        jar="$GABARITO/comprovante-$s/target/comprovante-$s-0.0.1-SNAPSHOT.jar"
        [[ -f "$jar" ]] || falha "$jar não existe (rode ./demo.sh preparar)"
        if [[ -f "$RUN_DIR/$s.pid" ]] && kill -0 "$(cat "$RUN_DIR/$s.pid")" 2>/dev/null; then
            echo "$s já está rodando"; continue
        fi
        extra=()
        [[ "$s" == notificacao ]] && extra=(--spring.profiles.active=demo)
        # O qpid-broker (Plano B) traz um logback.xml que silencia os logs: usamos o nosso.
        nohup "$JAVA21_HOME/bin/java" -jar "$jar" \
            --logging.config="$DEMO_DIR/logback-demo.xml" ${extra[@]+"${extra[@]}"} \
            > "$RUN_DIR/$s.log" 2>&1 &
        echo $! > "$RUN_DIR/$s.pid"
        echo "$s iniciado (porta $(porta "$s"), log em demo/.run/$s.log)"
    done
    passo "Aguardando os serviços responderem"
    for s in "${SERVICOS[@]}"; do
        local i
        for i in $(seq 1 60); do
            curl -sf "localhost:$(porta "$s")/actuator/health" >/dev/null && break
            sleep 1
        done
        printf '%-12s %s\n' "$s" "$(saude "$s" | grep . || echo FORA)"
    done
}

apps_down() {
    passo "Parando os serviços"
    local s
    for s in "${SERVICOS[@]}"; do
        if [[ -f "$RUN_DIR/$s.pid" ]]; then
            kill "$(cat "$RUN_DIR/$s.pid")" 2>/dev/null && echo "$s parado" || echo "$s já estava parado"
            rm -f "$RUN_DIR/$s.pid"
        fi
    done
}

lideres() {
    local rno kno
    rno="$(no_vivo rabbitmq rabbit)"; kno="$(no_vivo kafka kafka)"
    passo "RabbitMQ: filas (líder e membros)"
    if [[ -n "$rno" ]]; then
        RABBIT_NO="$rno" rabbitctl list_queues name type leader members messages
    else
        echo "nenhum nó de pé"
    fi
    passo "Kafka: tópico comprovante-gravado (líder e ISR por partição)"
    if [[ -n "$kno" ]]; then
        KAFKA_NO="$kno" kafka_topics --describe --topic comprovante-gravado | grep -v '^$'
    else
        echo "nenhum broker de pé"
    fi
}

status() {
    passo "Contêineres dos clusters"
    docker ps -a --filter name=cluster- --format 'table {{.Names}}\t{{.Status}}'
    passo "Serviços"
    local s
    for s in "${SERVICOS[@]}"; do
        printf '%-12s :%s  %s\n' "$s" "$(porta "$s")" "$(saude "$s" | grep . || echo FORA)"
    done
    lideres
}

no_do_cluster() {
    local tipo="$1" n="$2"
    [[ "$n" =~ ^[1-3]$ ]] || falha "nó deve ser 1, 2 ou 3"
    case "$tipo" in
        rabbit) echo "rabbitmq rabbit-$n" ;;
        kafka) echo "kafka kafka-$n" ;;
        *) falha "use: rabbit|kafka <1..3>" ;;
    esac
}

derruba() {
    read -r cluster servico <<< "$(no_do_cluster "${1:-}" "${2:-}")"
    passo "Parando $servico"
    docker compose -f "$CLUSTERS_DIR/$cluster/docker-compose.yml" stop "$servico"
}

levanta() {
    read -r cluster servico <<< "$(no_do_cluster "${1:-}" "${2:-}")"
    passo "Religando $servico"
    docker compose -f "$CLUSTERS_DIR/$cluster/docker-compose.yml" up -d --wait "$servico"
}

logs() {
    local s="${1:-}"
    [[ -n "$(porta "$s")" ]] || falha "use: logs emissor|gravador|consulta|notificacao"
    tail -n 30 -f "$RUN_DIR/$s.log"
}

case "${1:-}" in
    preparar) preparar ;;
    up) clusters_up; apps_up; status ;;
    down) apps_down; clusters_down ;;
    clusters-up) clusters_up ;;
    clusters-down) clusters_down ;;
    apps-up) apps_up ;;
    apps-down) apps_down ;;
    status) status ;;
    lideres) lideres ;;
    derruba) derruba "${2:-}" "${3:-}" ;;
    levanta) levanta "${2:-}" "${3:-}" ;;
    logs) logs "${2:-}" ;;
    *) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
