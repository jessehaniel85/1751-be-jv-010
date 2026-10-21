package br.com.caixa.pix.notificacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * O gateway externo de SMS/push (simulado): aqui é que a rede e o terceiro podem falhar.
 * Na demonstração (perfil "demo") ele pode cair ou ficar lento; desligado, só registra o envio.
 */
@Component
public class GatewayHttp {

    private static final Logger log = LoggerFactory.getLogger(GatewayHttp.class);

    private final AtomicBoolean falhar = new AtomicBoolean(false);
    private final AtomicLong lentidaoMs = new AtomicLong(0);

    public void post(String destino, UUID comprovanteId) {
        log.info("Enviando notificação do comprovante {} para {}", comprovanteId, destino);
        esperar(lentidaoMs.get());
        if (falhar.get()) {
            throw new IllegalStateException("gateway indisponível");
        }
    }

    private void esperar(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void configurar(boolean falhar, long lentidaoMs) {
        this.falhar.set(falhar);
        this.lentidaoMs.set(lentidaoMs);
    }

    public String estado() {
        return "falhar=" + falhar.get() + ", lentidao_ms=" + lentidaoMs.get();
    }
}
