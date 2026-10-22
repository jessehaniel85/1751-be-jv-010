package br.com.caixa.pix.consulta;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aula 8 — lado CONSUMIDOR: a consulta declara o que precisa do gravador e gera o pact.
 * O gravador (provedor) verifica esse pact contra o serviço real.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "comprovante-gravador", pactVersion = PactSpecVersion.V3)
class GravadorContractTest {

    /** O ObjectMapper como o Boot o monta nesta API (snake_case, application.yml). */
    private static final ObjectMapper MAPPER_DA_CONSULTA = Jackson2ObjectMapperBuilder.json()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    /** O GravadorClient com a configuração de produção (timeout e camelCase do gravador). */
    private static GravadorClient clienteDeProducao(MockServer mock) {
        return new GravadorClient(new GravadorClientConfig()
                .restClientBuilder(Duration.ofSeconds(2), MAPPER_DA_CONSULTA), mock.getUrl());
    }

    @Pact(consumer = "comprovante-consulta")
    RequestResponsePact comprovanteExiste(PactDslWithProvider builder) {
        return builder
            .given("comprovante 7f3a... existe e está gravado")   // provider state
            .uponReceiving("busca de comprovante por id existente")
                .path("/comprovantes/7f3a0e4c-1d2b-4c8a-9f01-2a3b4c5d6e7f")
                .method("GET")
            .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                    .uuid("id", "7f3a0e4c-1d2b-4c8a-9f01-2a3b4c5d6e7f")
                    .numberType("valor", 150.00)          // a consulta DEPENDE de "valor"
                    .stringType("chavePix", "joao@pix.com")
                    .datetime("dataHora", "yyyy-MM-dd'T'HH:mm:ss")
                    .stringMatcher("status", "GRAVADO|ACEITO", "GRAVADO"))
            .toPact();
    }

    @Pact(consumer = "comprovante-consulta")
    RequestResponsePact comprovanteInexistente(PactDslWithProvider builder) {
        return builder
            .given("comprovante 0000... não existe")
            .uponReceiving("busca de comprovante por id inexistente")
                .path("/comprovantes/00000000-0000-0000-0000-000000000000")
                .method("GET")
            .willRespondWith()
                .status(404)
            .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "comprovanteExiste")
    void leRespostaDoGravador(MockServer mock) {
        var client = clienteDeProducao(mock);

        var dto = client.buscarPorId(
            UUID.fromString("7f3a0e4c-1d2b-4c8a-9f01-2a3b4c5d6e7f"));

        assertThat(dto).isPresent();
        assertThat(dto.get().valor()).isEqualByComparingTo("150.00");  // valida o que importa
        assertThat(dto.get().chavePix()).isEqualTo("joao@pix.com");    // nome composto: pega snake × camel
        assertThat(dto.get().dataHora()).isNotNull();
    }

    @Test
    @PactTestFor(pactMethod = "comprovanteInexistente")
    void inexistenteViraVazio(MockServer mock) {
        var client = clienteDeProducao(mock);

        assertThat(client.buscarPorId(UUID.fromString("00000000-0000-0000-0000-000000000000"))).isEmpty();
    }
}
