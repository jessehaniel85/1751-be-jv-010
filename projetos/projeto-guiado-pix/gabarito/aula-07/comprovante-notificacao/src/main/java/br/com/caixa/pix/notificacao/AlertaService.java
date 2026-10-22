package br.com.caixa.pix.notificacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Alerta de falha permanente (evento na DLT): aqui, um log de erro e um contador. */
@Service
public class AlertaService {

    private static final Logger log = LoggerFactory.getLogger(AlertaService.class);

    private final AtomicInteger falhasPermanentes = new AtomicInteger();

    public void notificarFalhaPermanente(UUID comprovanteId) {
        falhasPermanentes.incrementAndGet();
        log.error("Comprovante {} caiu na DLT do tópico de notificação: falha permanente, precisa de olhar humano",
                comprovanteId);
    }

    public int falhasPermanentes() {
        return falhasPermanentes.get();
    }
}
