package br.com.caixa.pix.gravador;

import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Aula 5 — consome a fila de gravação distinguindo os dois tipos de falha:
 * permanente (mensagem malformada) vai direto para a DLQ; transitória (banco) passa pelo retry.
 * A DLQ não tem consumidor: as mensagens ficam lá, preservadas para inspeção no painel do RabbitMQ.
 */
@Component
public class GravacaoListener {

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
}
