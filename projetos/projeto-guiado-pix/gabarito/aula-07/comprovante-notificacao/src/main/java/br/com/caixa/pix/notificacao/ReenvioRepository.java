package br.com.caixa.pix.notificacao;

import org.springframework.stereotype.Repository;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Notificações que o fallback deixou para reenviar depois (em memória). */
@Repository
public class ReenvioRepository {

    private final Queue<UUID> pendentes = new ConcurrentLinkedQueue<>();

    public void agendar(UUID comprovanteId) {
        pendentes.add(comprovanteId);
    }

    public int pendentes() {
        return pendentes.size();
    }
}
