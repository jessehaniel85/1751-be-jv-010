package br.com.caixa.pix.contracts;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Projeção de leitura de um comprovante já gravado (GET). */
public record ComprovanteView(
        UUID id,
        BigDecimal valor,
        String chavePix,
        LocalDateTime dataHora,
        String status,
        String nome,
        String numeroDocumento,
        LocalDateTime dataHoraGravacao
) {
}
