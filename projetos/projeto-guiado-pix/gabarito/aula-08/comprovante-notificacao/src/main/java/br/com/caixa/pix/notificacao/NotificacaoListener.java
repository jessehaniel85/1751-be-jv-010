package br.com.caixa.pix.notificacao;

import br.com.caixa.pix.contracts.ComprovanteGravadoEvent;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Aula 7 — consumo resiliente: @RetryableTopic faz o retry NÃO-bloqueante em tópicos auxiliares,
 * com backoff exponencial e jitter (random); esgotadas as tentativas, o evento vai para a DLT.
 */
@Component
public class NotificacaoListener {

    private final NotificacaoService notificacaoService;
    private final AlertaService alertaService;

    public NotificacaoListener(NotificacaoService notificacaoService, AlertaService alertaService) {
        this.notificacaoService = notificacaoService;
        this.alertaService = alertaService;
    }

    @RetryableTopic(
            attempts = "4",                                                 // 1 original + 3 retries
            backoff = @Backoff(delay = 1000, multiplier = 2.0, random = true), // ~1s, 2s, 4s, com jitter
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE, // -retry-0, -1, -2 (com jitter, o atraso não serve de nome)
            dltStrategy = DltStrategy.FAIL_ON_ERROR)                         // esgotou → DLT
    @KafkaListener(topics = "comprovante-gravado", groupId = "notificacao")
    public void aoGravar(ComprovanteGravadoEvent e) {
        notificacaoService.avisar(e); // idempotente → retry seguro
    }

    @DltHandler
    public void tratarDlt(ComprovanteGravadoEvent e) {
        // teto de tentativas atingido: parar e pedir olhar humano
        alertaService.notificarFalhaPermanente(e.comprovanteId());
    }
}
