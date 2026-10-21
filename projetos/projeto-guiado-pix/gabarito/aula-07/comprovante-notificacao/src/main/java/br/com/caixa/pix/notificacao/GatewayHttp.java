package br.com.caixa.pix.notificacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** O gateway externo de SMS/push (simulado): aqui é que a rede e o terceiro podem falhar. */
@Component
public class GatewayHttp {

    private static final Logger log = LoggerFactory.getLogger(GatewayHttp.class);

    public void post(String destino, UUID comprovanteId) {
        log.info("Enviando notificação do comprovante {} para {}", comprovanteId, destino);
        // throw new IllegalStateException("gateway indisponível"); // descomente para exercitar reenvio, retry → DLT e breaker
    }
}
