package br.com.caixa.pix.contracts;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payload de emissão de comprovante PIX (corpo do POST /comprovantes).
 *
 * <p>Campos em snake_case no JSON — o mapeamento é feito pela configuração global do
 * Jackson nos serviços (PropertyNamingStrategy SNAKE_CASE), mantendo este contrato sem
 * dependências de serialização.</p>
 *
 * <p>TODO (Aula 1): este é o agregado central do bounded context "emissão".
 * TODO (Aula 4/5): vira o corpo da mensagem publicada na fila de gravação.</p>
 */
public record ComprovanteRequest(
        String nome,
        TipoDocumento tipoDocumento,
        String numeroDocumento,
        String numeroAgencia,        // 4 caracteres
        String numeroConta,          // 5 caracteres
        String digitoVerificadorConta, // 1 caractere
        BigDecimal valorTransacao,
        TipoChave tipoChavePixDestino,
        String chavePixDestino,
        String nomeClienteDestino,
        String identificacaoPix,
        LocalDateTime dataHoraTransacao
) {
}
