package br.com.caixa.pix.gravador;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import br.com.caixa.pix.contracts.ComprovanteRequest;
import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import br.com.caixa.pix.contracts.TipoChave;
import br.com.caixa.pix.contracts.TipoDocumento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Aula 8 — lado PROVEDOR (gravador). Verifica o pact gerado pela consulta contra o serviço real.
 * Roda o app em porta aleatória e confirma que ele honra o contrato.
 * (Requer subir o contexto Spring; em ambiente sem broker, use um profile de teste que
 *  desabilite a mensageria — ver ESTADO.md.)
 */
@Provider("comprovante-gravador")
@PactFolder("src/test/resources/pacts")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GravadorProviderTest {

    @LocalServerPort
    int port;

    @Autowired
    ComprovanteRepository repository;

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verificaContrato(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @State("comprovante 11111111-1111-1111-1111-111111111111 existe")
    void comprovanteExiste() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        if (!repository.existsById(id)) {
            Comprovante comprovante = Comprovante.aPartirDe(new GravarComprovanteCommand(id, new ComprovanteRequest(
                    "Giovanni", TipoDocumento.CPF, "50329291076", "2022", "00276", "0",
                    new BigDecimal("23.99"), TipoChave.CELULAR, "11948755536",
                    "Fernando", "churrasco", LocalDateTime.now())));
            comprovante.marcarGravado();
            repository.save(comprovante);
        }
    }
}
