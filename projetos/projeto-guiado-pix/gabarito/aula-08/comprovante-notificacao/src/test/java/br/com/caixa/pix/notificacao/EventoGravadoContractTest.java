package br.com.caixa.pix.notificacao;

import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.annotations.Pact;
import au.com.dius.pact.core.model.messaging.Message;
import au.com.dius.pact.core.model.messaging.MessagePact;
import au.com.dius.pact.consumer.MessagePactBuilder;
import br.com.caixa.pix.contracts.ComprovanteGravadoEvent;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aula 8 — message pact, lado CONSUMIDOR: a notificação (e os reatores do mesmo módulo)
 * declaram o evento ComprovanteGravado que esperam do tópico. O gravador verifica sem broker.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "comprovante-gravador", providerType = ProviderType.ASYNCH,
        pactVersion = PactSpecVersion.V3)
class EventoGravadoContractTest {

    /** Instante em ISO-8601 (UTC), com ou sem fração de segundo: é como o gravador o publica. */
    private static final String ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

    @Pact(consumer = "comprovante-notificacao")
    MessagePact eventoGravado(MessagePactBuilder builder) {
        return builder
            .expectsToReceive("evento de comprovante gravado")
            .withContent(new PactDslJsonBody()
                .uuid("comprovanteId")
                .stringType("chavePixDestino")
                .numberType("valor")              // antifraude e BI, no mesmo módulo, usam o valor
                .stringMatcher("gravadoEm", ISO_INSTANT, "2026-10-21T18:00:00Z"))
            .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "eventoGravado")
    void consomeOEvento(List<Message> mensagens) {
        // o mesmo desserializador do consumidor real (application.yml)
        try (Deserializer<ComprovanteGravadoEvent> json = new JsonDeserializer<>(ComprovanteGravadoEvent.class, false)) {
            var evento = json.deserialize("comprovante-gravado", mensagens.get(0).contentsAsBytes());

            assertThat(evento.comprovanteId()).isNotNull();
            assertThat(evento.chavePixDestino()).isNotBlank();
            assertThat(evento.valor()).isNotNull();
            assertThat(evento.gravadoEm()).isNotNull();
        }
    }
}
