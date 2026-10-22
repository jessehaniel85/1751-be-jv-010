package br.com.caixa.pix.notificacao;

import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Notificações que o fallback deixou para reenviar quando o gateway voltar (em memória). */
@Repository
public class ReenvioRepository {

    private final Map<UUID, String> pendentes = new ConcurrentHashMap<>(); // id do comprovante → destino

    public void agendar(UUID comprovanteId, String destino) {
        pendentes.put(comprovanteId, destino);
    }

    /** Entrega os pendentes e os tira da fila (quem não for enviado volta pelo fallback). */
    public Map<UUID, String> retirarTodos() {
        Map<UUID, String> lote = new HashMap<>();
        pendentes.keySet().forEach(id -> {
            String destino = pendentes.remove(id);
            if (destino != null) lote.put(id, destino);
        });
        return lote;
    }

    public int pendentes() {
        return pendentes.size();
    }
}
