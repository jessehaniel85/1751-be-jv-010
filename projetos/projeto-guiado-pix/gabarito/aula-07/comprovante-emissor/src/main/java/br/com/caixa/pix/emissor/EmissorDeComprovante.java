package br.com.caixa.pix.emissor;

import br.com.caixa.pix.contracts.ComprovanteRequest;
import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Bounded context EMISSÃO — Aula 4, assíncrono: valida, gera o id e PUBLICA o comando
 * de gravação na exchange. A gravação acontece depois, no consumidor (idempotente).
 */
@Service
public class EmissorDeComprovante {

    private final RabbitTemplate rabbit;

    public EmissorDeComprovante(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public UUID aceitarEPublicar(ComprovanteRequest req) {
        validarRegrasDeNegocio(req);  // síncrono e rápido: formato, obrigatórios, limites
        UUID id = UUID.randomUUID();   // identidade gerada AQUI, na aceitação

        rabbit.convertAndSend(
                MessagingConfig.EXCHANGE,  // exchange (não a fila diretamente)
                MessagingConfig.ROUTING,   // routing key = a intenção
                new GravarComprovanteCommand(id, req));

        return id; // devolvido no 202, para o cliente consultar depois
    }

    private void validarRegrasDeNegocio(ComprovanteRequest r) {
        exigir(r.nome(), "nome");
        exigir(r.numeroDocumento(), "numero_documento");
        if (r.tipoDocumento() == null) throw new ComprovanteInvalidoException("tipo_documento é obrigatório");
        tamanho(r.numeroAgencia(), 4, "numero_agencia");
        tamanho(r.numeroConta(), 5, "numero_conta");
        tamanho(r.digitoVerificadorConta(), 1, "digito_verificador_conta");
        if (r.valorTransacao() == null || r.valorTransacao().compareTo(BigDecimal.ZERO) <= 0)
            throw new ComprovanteInvalidoException("valor_transacao deve ser maior que zero");
        if (r.tipoChavePixDestino() == null) throw new ComprovanteInvalidoException("tipo_chave_pix_destino é obrigatório");
        exigir(r.chavePixDestino(), "chave_pix_destino");
    }

    private void exigir(String v, String c) {
        if (v == null || v.isBlank()) throw new ComprovanteInvalidoException(c + " é obrigatório");
    }

    private void tamanho(String v, int n, String c) {
        if (v == null || v.length() != n) throw new ComprovanteInvalidoException(c + " deve ter " + n + " caractere(s)");
    }
}
