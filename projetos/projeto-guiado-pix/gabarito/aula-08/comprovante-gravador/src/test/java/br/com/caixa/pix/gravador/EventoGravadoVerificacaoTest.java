package br.com.caixa.pix.gravador;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Consumer;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import br.com.caixa.pix.contracts.ComprovanteGravadoEvent;
import br.com.caixa.pix.contracts.ComprovanteRequest;
import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import br.com.caixa.pix.contracts.TipoChave;
import br.com.caixa.pix.contracts.TipoDocumento;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Aula 8 — message pact, lado PROVEDOR: roda o código que produz o evento (GravadorService)
 * e confere o payload contra o contrato da notificação, sem broker real.
 */
@Provider("comprovante-gravador")
@Consumer("comprovante-notificacao")
@PactFolder("pacts")
class EventoGravadoVerificacaoTest {

    @BeforeEach
    void target(PactVerificationContext ctx) {
        ctx.setTarget(new MessageTestTarget());
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verificaContrato(PactVerificationContext ctx) {
        ctx.verifyInteraction();
    }

    @PactVerifyProvider("evento de comprovante gravado")
    @SuppressWarnings("unchecked")
    MessageAndMetadata eventoGravado() {
        ComprovanteRepository repo = mock(ComprovanteRepository.class);
        KafkaTemplate<String, ComprovanteGravadoEvent> kafka = mock(KafkaTemplate.class);
        new GravadorService(repo, kafka).gravar(new GravarComprovanteCommand(UUID.randomUUID(),
                new ComprovanteRequest("João", TipoDocumento.CPF, "50329291076", "2022", "00276", "0",
                        new BigDecimal("150.00"), TipoChave.EMAIL, "joao@pix.com",
                        "Maria", "E00000000202610211500", LocalDateTime.parse("2026-10-21T15:00:00"))));

        var evento = ArgumentCaptor.forClass(ComprovanteGravadoEvent.class);
        verify(kafka).send(eq(KafkaConfig.TOPICO), anyString(), evento.capture());
        // o serializador do produtor real (KafkaConfig), com o ObjectMapper como o Boot o configura
        var mapperDoBoot = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        try (Serializer<Object> json = KafkaConfig.serializadorDoEvento(mapperDoBoot)) {
            byte[] payload = json.serialize(KafkaConfig.TOPICO, evento.getValue());
            return new MessageAndMetadata(payload, Map.of("contentType", "application/json"));
        }
    }
}
