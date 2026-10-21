package br.com.caixa.pix.notificacao;

import br.com.caixa.pix.contracts.ComprovanteGravadoEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Aula 7 — chamada a um gateway externo instável, protegida por circuit breaker.
 * Aberto o circuito, o fallback evita martelar a dependência doente.
 */
@Component
public class NotificacaoGateway {

    private static final Logger log = LoggerFactory.getLogger(NotificacaoGateway.class);

    // Demonstração: ligados pelo GatewayDemoController (perfil "demo"); desligados, nada muda.
    private final AtomicBoolean simularFalha = new AtomicBoolean(false);
    private final AtomicBoolean propagarFalha = new AtomicBoolean(false);

    @CircuitBreaker(name = "notificacao-gateway", fallbackMethod = "fallback")
    public void enviar(ComprovanteGravadoEvent evento) {
        // Simula chamada externa que pode falhar (rede/gateway).
        log.info("Enviando notificação do comprovante {}", evento.identificadorComprovante());
        if (simularFalha.get()) {
            throw new IllegalStateException("gateway indisponível");
        }
    }

    /**
     * Acionado quando o circuito está aberto ou a chamada falha.
     * Se só registrar, a falha some aqui: o @RetryableTopic não vê erro e a notificação se perde.
     * Propagando, o consumo falha e o evento segue para os tópicos de retry e, por fim, a DLT.
     */
    private void fallback(ComprovanteGravadoEvent evento, Throwable causa) {
        log.warn("Fallback de notificação do comprovante {}: {}",
                evento.identificadorComprovante(), causa.getMessage());
        if (propagarFalha.get()) {
            throw new IllegalStateException("notificação não enviada: " + causa.getMessage(), causa);
        }
    }

    public void configurar(boolean simularFalha, boolean propagarFalha) {
        this.simularFalha.set(simularFalha);
        this.propagarFalha.set(propagarFalha);
    }

    public String estado() {
        return "simular_falha=" + simularFalha.get() + ", propagar_falha=" + propagarFalha.get();
    }
}
