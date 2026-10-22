package br.com.caixa.pix.gravador;

import au.com.dius.pact.consumer.MessagePactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.annotations.Pact;
import au.com.dius.pact.core.model.messaging.Message;
import au.com.dius.pact.core.model.messaging.MessagePact;
import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aula 8 — message pact do COMANDO na fila, lado CONSUMIDOR: o gravador declara o formato
 * (snake_case) que lê do emissor. É o teste que teria pegado, no build, o defeito de 21/10/2026
 * (emissor em snake_case, gravador lendo camelCase: todo comprovante caía na DLQ).
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "comprovante-emissor", providerType = ProviderType.ASYNCH,
        pactVersion = PactSpecVersion.V3)
class ComandoGravacaoContractTest {

    @Pact(consumer = "comprovante-gravador")
    MessagePact comandoGravar(MessagePactBuilder builder) {
        return builder
            .expectsToReceive("comando de gravação de comprovante")
            .withContent(new PactDslJsonBody()
                .uuid("identificador_comprovante")
                .object("dados")
                    .stringType("nome")
                    .stringType("numero_documento")
                    .numberType("valor_transacao")
                    .stringMatcher("tipo_chave_pix_destino", "CELULAR|EMAIL|CPF|CNPJ|ALEATORIA", "EMAIL")
                    .stringType("chave_pix_destino")
                    .datetime("data_hora_transacao", "yyyy-MM-dd'T'HH:mm:ss")
                .closeObject())
            .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "comandoGravar")
    void leOComando(List<Message> mensagens) {
        // o mesmo conversor do listener real (MessagingConfig)
        var conversor = new MessagingConfig().jsonMessageConverter(Jackson2ObjectMapperBuilder.json().build());
        var mensagem = MessageBuilder.withBody(mensagens.get(0).contentsAsBytes())
                .setContentType("application/json").build();
        mensagem.getMessageProperties().setInferredArgumentType(GravarComprovanteCommand.class);

        var cmd = (GravarComprovanteCommand) conversor.fromMessage(mensagem);

        assertThat(cmd.identificadorComprovante()).isNotNull();
        assertThat(cmd.dados().valorTransacao()).isNotNull();
        assertThat(cmd.dados().chavePixDestino()).isNotBlank();
        assertThat(cmd.dados().dataHoraTransacao()).isNotNull();
    }
}
