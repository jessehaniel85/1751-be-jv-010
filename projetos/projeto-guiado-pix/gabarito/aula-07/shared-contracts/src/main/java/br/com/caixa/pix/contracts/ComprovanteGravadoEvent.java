package br.com.caixa.pix.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento publicado no tópico Kafka pelo gravador após persistir (Aula 6).
 * Um FATO (já aconteceu), com os dados completos para quem reage não precisar voltar perguntar.
 * Lido por vários consumer groups (notificação, antifraude, BI, compliance) sem o gravador conhecê-los.
 */
public record ComprovanteGravadoEvent(
        UUID comprovanteId,
        String chavePixDestino,
        BigDecimal valor,
        Instant gravadoEm) {
}
