package br.com.caixa.pix.emissor;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import br.com.caixa.pix.contracts.ComprovanteRequest;
import br.com.caixa.pix.contracts.TipoChave;
import br.com.caixa.pix.contracts.TipoDocumento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Aula 8 — message pact do COMANDO, lado PROVEDOR: roda o EmissorDeComprovante real, captura o
 * comando publicado e o converte com o MessageConverter configurado no emissor (o mesmo da produção).
 */
@Provider("comprovante-emissor")
@PactFolder("pacts")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ComandoGravacaoVerificacaoTest {

    @MockBean RabbitTemplate rabbit;
    @Autowired EmissorDeComprovante emissor;
    @Autowired MessageConverter conversor;

    @BeforeEach
    void target(PactVerificationContext ctx) {
        ctx.setTarget(new MessageTestTarget());
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verificaContrato(PactVerificationContext ctx) {
        ctx.verifyInteraction();
    }

    @PactVerifyProvider("comando de gravação de comprovante")
    MessageAndMetadata comandoGravar() {
        emissor.aceitarEPublicar(new ComprovanteRequest("João", TipoDocumento.CPF, "50329291076",
                "2022", "00276", "0", new BigDecimal("150.00"), TipoChave.EMAIL, "joao@pix.com",
                "Maria", "E00000000202610211500", LocalDateTime.parse("2026-10-21T15:00:00")));

        var comando = ArgumentCaptor.forClass(Object.class);
        verify(rabbit).convertAndSend(eq(MessagingConfig.EXCHANGE), eq(MessagingConfig.ROUTING), comando.capture());
        var mensagem = conversor.toMessage(comando.getValue(), new MessageProperties());
        return new MessageAndMetadata(mensagem.getBody(), Map.of("contentType", "application/json"));
    }
}
