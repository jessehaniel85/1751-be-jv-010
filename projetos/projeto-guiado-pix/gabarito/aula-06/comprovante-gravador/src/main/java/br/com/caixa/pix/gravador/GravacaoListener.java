package br.com.caixa.pix.gravador;

import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Aula 5 — consome a fila de gravação distinguindo os dois tipos de falha:
 * permanente (mensagem malformada) vai direto para a DLQ; transitória (banco) passa pelo retry.
 */
@Component
public class GravacaoListener {

    private static final Logger log = LoggerFactory.getLogger(GravacaoListener.class);

    private final GravadorService gravador;

    public GravacaoListener(GravadorService gravador) {
        this.gravador = gravador;
    }

    @RabbitListener(queues = MessagingConfig.FILA)
    public void aoReceber(GravarComprovanteCommand cmd) {
        if (cmd.identificadorComprovante() == null || cmd.dados() == null
                || cmd.dados().valorTransacao() == null) {
            // falha PERMANENTE: nenhum retry vai consertar isto
            throw new AmqpRejectAndDontRequeueException("comprovante malformado");
        }
        gravador.gravar(cmd); // idempotente sob redelivery; pode lançar falha TRANSITÓRIA → retry
    }

    /** Mensagens "envenenadas" param aqui para inspeção (nunca descartadas em silêncio). */
    @RabbitListener(queues = MessagingConfig.DLQ)
    public void aoCairNaDlq(GravarComprovanteCommand cmd) {
        log.warn("Comprovante {} caiu na DLQ", cmd.identificadorComprovante());
    }
}
