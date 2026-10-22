package br.com.caixa.pix.consulta;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** O comprovante como a CONSULTA o lê do gravador e o devolve na 2ª via. */
public record ComprovanteDTO(
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
