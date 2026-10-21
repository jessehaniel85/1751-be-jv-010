package br.com.caixa.pix.notificacao;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Aula 7 — a chamada ao gateway, protegida:
 * bulkhead (pool de threads próprio), circuit breaker (falhar rápido) e rate limiter (cota do terceiro).
 * O bulkhead do tipo THREADPOOL só se aplica a métodos que devolvem CompletableFuture.
 */
@Service
public class GatewayCliente {

    private static final Logger log = LoggerFactory.getLogger(GatewayCliente.class);

    private final GatewayHttp gatewayHttp;
    private final ReenvioRepository reenvioRepository;

    // Demonstração (perfil "demo"): em vez de degradar, devolve a falha para o retry e a DLT.
    private final AtomicBoolean propagarFalha = new AtomicBoolean(false);

    public GatewayCliente(GatewayHttp gatewayHttp, ReenvioRepository reenvioRepository) {
        this.gatewayHttp = gatewayHttp;
        this.reenvioRepository = reenvioRepository;
    }

    @Bulkhead(name = "gatewayNotificacao", type = Bulkhead.Type.THREADPOOL)
    @CircuitBreaker(name = "gatewayNotificacao", fallbackMethod = "fallback")
    @RateLimiter(name = "gatewayNotificacao")
    public CompletableFuture<Void> enviar(String destino, UUID comprovanteId) {
        gatewayHttp.post(destino, comprovanteId); // pode estourar timeout / 503
        return CompletableFuture.completedFuture(null);
    }

    // chamado quando o circuito está aberto OU a chamada falha (inclusive bulkhead cheio)
    private CompletableFuture<Void> fallback(String destino, UUID comprovanteId, Throwable t) {
        if (propagarFalha.get()) {
            log.warn("Gateway indisponível ({}). Devolvendo a falha do comprovante {}", t.getMessage(), comprovanteId);
            return CompletableFuture.failedFuture(t); // o consumo falha → tópicos de retry → DLT
        }
        log.warn("Gateway indisponível ({}). Enfileirando para reenvio: {}", t.getMessage(), comprovanteId);
        reenvioRepository.agendar(comprovanteId); // degrada com elegância, não derruba
        return CompletableFuture.completedFuture(null);
    }

    public void propagarFalha(boolean propagar) {
        propagarFalha.set(propagar);
    }

    public boolean propagaFalha() {
        return propagarFalha.get();
    }
}
