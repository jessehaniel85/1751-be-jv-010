package br.com.caixa.pix.gravador;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Consumer;
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

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/** Aula 8 — lado PROVEDOR: verifica o pact da consulta contra o gravador real (replay de cada interação). */
@Provider("comprovante-gravador")
@Consumer("comprovante-consulta")
@PactFolder("pacts")                       // lê os pact files gerados pelo consumer
@SpringBootTest(webEnvironment = RANDOM_PORT)
class GravadorVerificacaoTest {

    private static final UUID EXISTENTE = UUID.fromString("7f3a0e4c-1d2b-4c8a-9f01-2a3b4c5d6e7f");
    private static final UUID INEXISTENTE = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @LocalServerPort int porta;
    @Autowired ComprovanteRepository repo;

    @BeforeEach
    void target(PactVerificationContext ctx) {
        ctx.setTarget(new HttpTestTarget("localhost", porta));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verificaContrato(PactVerificationContext ctx) {
        ctx.verifyInteraction();           // replay de cada interação do pact
    }

    // provider state: semeia o dado que o pact assume existir.
    @State("comprovante 7f3a... existe e está gravado")
    void semeiaComprovante() {
        if (repo.existsById(EXISTENTE)) {
            return;
        }
        Comprovante comprovante = Comprovante.aPartirDe(new GravarComprovanteCommand(EXISTENTE,
                new ComprovanteRequest("João", TipoDocumento.CPF, "50329291076", "2022", "00276", "0",
                        new BigDecimal("150.00"), TipoChave.EMAIL, "joao@pix.com",
                        "Maria", "E00000000202610211500", LocalDateTime.parse("2026-10-21T15:00:00"))));
        comprovante.marcarGravado();
        repo.save(comprovante);
    }

    // provider state: garante a ausência que o pact do 404 assume.
    @State("comprovante 0000... não existe")
    void garanteAusencia() {
        repo.deleteById(INEXISTENTE);
    }
}
