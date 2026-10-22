package br.com.caixa.pix.notificacao;

import br.com.caixa.pix.contracts.ComprovanteGravadoEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Aula 6 — consumer group "antifraude": lê os MESMOS eventos, com o seu próprio offset. */
@Component
public class AntifraudeListener {

    private final AntifraudeService antifraudeService;

    public AntifraudeListener(AntifraudeService antifraudeService) {
        this.antifraudeService = antifraudeService;
    }

    @KafkaListener(topics = "comprovante-gravado", groupId = "antifraude")
    public void analisar(ComprovanteGravadoEvent e) {
        antifraudeService.avaliarPadrao(e.comprovanteId(), e.valor());
    }
}
