package br.com.caixa.pix.notificacao;

import br.com.caixa.pix.contracts.ComprovanteGravadoEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Aula 6 — consumer group "notificacao": avisa o cliente de cada comprovante gravado. */
@Component
public class NotificacaoListener {

    private final NotificacaoService notificacaoService;

    public NotificacaoListener(NotificacaoService notificacaoService) {
        this.notificacaoService = notificacaoService;
    }

    @KafkaListener(topics = "comprovante-gravado", groupId = "notificacao")
    public void aoGravar(ComprovanteGravadoEvent e) {
        notificacaoService.avisarCliente(e.comprovanteId(), e.chavePixDestino());
    }
}
