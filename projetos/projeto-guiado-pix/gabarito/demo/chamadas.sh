#!/usr/bin/env bash
# Chamadas curl da demonstração, na ordem do roteiro. Os serviços precisam estar de pé
# (./demo.sh up). O último id emitido fica em .run/ultimo-id para as consultas.
#
#   ./chamadas.sh emitir [n]        # emite n comprovantes válidos (padrão 1) → 202 + id
#   ./chamadas.sh invalido          # emite com agência de 3 dígitos → 400
#   ./chamadas.sh consultar [id]    # GET na consulta (cache + gravador) → 200 ou 404
#   ./chamadas.sh interno [id]      # GET direto no gravador, /comprovantes/{id} na 8082 (o contrato do PACT)
#   ./chamadas.sh saude             # health dos 4 serviços
#   ./chamadas.sh falha on|propaga|off   # gateway da notificação: falha com reenvio agendado, falha até a DLT, normal
#   ./chamadas.sh lento [ms]        # gateway lento (padrão 2000 ms), para o bulkhead
#   ./chamadas.sh bulkhead [n]      # n chamadas simultâneas ao gateway (padrão 10): atendidas × recusadas
#   ./chamadas.sh reatores          # BI, compliance, reenvios agendados e falhas permanentes (DLT)
#   ./chamadas.sh circuito          # estado do circuit breaker gatewayNotificacao
#   ./chamadas.sh rajada [n] [s]    # emite n comprovantes, um a cada s segundos (padrão 20 e 1)

set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_DIR="$DEMO_DIR/.run"
EMISSOR=localhost:8081
GRAVADOR=localhost:8082
CONSULTA=localhost:8083
NOTIFICACAO=localhost:8084
mkdir -p "$RUN_DIR"

corpo() {
    local agencia="${1:-0001}"
    cat <<EOF
{
  "nome": "Maria Silva",
  "tipo_documento": "CPF",
  "numero_documento": "12345678900",
  "numero_agencia": "$agencia",
  "numero_conta": "12345",
  "digito_verificador_conta": "6",
  "valor_transacao": 150.75,
  "tipo_chave_pix_destino": "EMAIL",
  "chave_pix_destino": "joao@exemplo.com",
  "nome_cliente_destino": "Joao Souza",
  "identificacao_pix": "E00000000202610211500",
  "data_hora_transacao": "2026-10-21T15:00:00"
}
EOF
}

ultimo_id() { cat "$RUN_DIR/ultimo-id" 2>/dev/null || { echo "nenhum id emitido ainda" >&2; exit 1; }; }

emitir_um() {
    local resposta id
    resposta="$(curl -s -w '\n%{http_code}' -X POST "$EMISSOR/comprovantes" \
        -H 'Content-Type: application/json' -d "$(corpo)")"
    id="$(echo "$resposta" | head -1 | sed -nE 's/.*"identificador_comprovante":"([^"]+)".*/\1/p')"
    echo "HTTP $(echo "$resposta" | tail -1)  id=$id"
    [[ -n "$id" ]] && echo "$id" > "$RUN_DIR/ultimo-id"
    return 0
}

case "${1:-}" in
    emitir)
        for _ in $(seq 1 "${2:-1}"); do emitir_um; done ;;
    invalido)
        curl -s -w '\nHTTP %{http_code}\n' -X POST "$EMISSOR/comprovantes" \
            -H 'Content-Type: application/json' -d "$(corpo 001)" ;;
    consultar)
        curl -s -w '\nHTTP %{http_code}\n' "$CONSULTA/comprovantes/${2:-$(ultimo_id)}" ;;
    interno)
        curl -s -w '\nHTTP %{http_code}\n' "$GRAVADOR/comprovantes/${2:-$(ultimo_id)}" ;;
    saude)
        for alvo in "emissor $EMISSOR" "gravador $GRAVADOR" "consulta $CONSULTA" "notificacao $NOTIFICACAO"; do
            set -- $alvo
            printf '%-12s %s\n' "$1" "$(curl -s "$2/actuator/health" | cut -c1-15 || echo FORA)"
        done ;;
    falha)
        case "${2:-}" in
            on) q="falhar=true&propagar=false" ;;
            propaga) q="falhar=true&propagar=true" ;;
            off) q="falhar=false&propagar=false" ;;
            *) echo "use: falha on|propaga|off" >&2; exit 1 ;;
        esac
        curl -s -X POST "$NOTIFICACAO/demo/gateway?$q"; echo ;;
    lento)
        curl -s -X POST "$NOTIFICACAO/demo/gateway?lentidao=${2:-2000}"; echo ;;
    bulkhead)
        curl -s -X POST "$NOTIFICACAO/demo/gateway/rajada?n=${2:-10}"; echo ;;
    reatores)
        curl -s "$NOTIFICACAO/demo/reatores"; echo ;;
    circuito)
        curl -s "$NOTIFICACAO/actuator/circuitbreakers" \
            | grep -oE '"(state|failureRate|bufferedCalls|failedCalls|notPermittedCalls)":"?[^,"]*' \
            | tr -d '"' | paste -sd ' ' - ;;
    rajada)
        for _ in $(seq 1 "${2:-20}"); do emitir_um || true; sleep "${3:-1}"; done ;;
    *)
        awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; exit 1 ;;
esac
