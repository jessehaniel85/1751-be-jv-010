package br.com.caixa.pix.notificacao;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Drena o reenvio quando o gateway volta: o circuito passa de HALF_OPEN para CLOSED
 * e cada pendente é enviado de novo, sem duplicar (idempotência do NotificacaoRepository).
 */
@Service
public class ReenvioService {

    private static final Logger log = LoggerFactory.getLogger(ReenvioService.class);

    private final ReenvioRepository reenvios;
    private final NotificacaoRepository notificados;
    private final GatewayCliente gateway;

    public ReenvioService(CircuitBreakerRegistry registry, ReenvioRepository reenvios,
                          NotificacaoRepository notificados, GatewayCliente gateway) {
        this.reenvios = reenvios;
        this.notificados = notificados;
        this.gateway = gateway;
        registry.circuitBreaker("gatewayNotificacao").getEventPublisher().onStateTransition(e -> {
            if (e.getStateTransition().getToState() == CircuitBreaker.State.CLOSED) {
                drenar();
            }
        });
    }

    /** Um por vez e fora da thread do circuito: uma rajada de reenvios não pode estourar o próprio bulkhead. */
    public void drenar() {
        Map<UUID, String> lote = reenvios.retirarTodos();
        if (lote.isEmpty()) {
            return;
        }
        log.info("Gateway de volta: reenviando {} notificação(ões) pendente(s)", lote.size());
        CompletableFuture.runAsync(() -> lote.forEach(this::reenviar));
    }

    private void reenviar(UUID id, String destino) {
        if (notificados.jaNotificado(id)) {
            return;
        }
        try {
            gateway.enviar(destino, id).join();
            notificados.marcarNotificado(id);
        } catch (CompletionException falhou) {
            // o fallback já o devolveu à fila de reenvio
        }
    }
}
