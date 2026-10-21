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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aula 8 — lado CONSUMIDOR (consulta declara o que precisa do gravador).
 * Gera o pact file que o provedor (gravador) verifica.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "comprovante-gravador", pactVersion = PactSpecVersion.V3)
class GravadorContractTest {

    private static final String ID = "11111111-1111-1111-1111-111111111111";

    @Pact(consumer = "comprovante-consulta")
    public RequestResponsePact comprovanteExiste(PactDslWithProvider builder) {
        return builder
                .given("comprovante " + ID + " existe")
                .uponReceiving("consulta por id existente")
                .path("/interno/comprovantes/" + ID)
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .uuid("identificadorComprovante", ID)
                        .stringType("nome", "Giovanni")
                        .stringType("numeroDocumento", "50329291076")
                        .decimalType("valorTransacao", 23.99)
                        .stringType("chavePixDestino", "11948755536"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "comprovanteExiste")
    void consultaHonraOContrato(MockServer mockServer) {
        GravadorReadClient client = new GravadorReadClient(mockServer.getUrl());
        assertTrue(client.buscar(UUID.fromString(ID)).isPresent());
    }
}
