package br.com.caixa.pix.notificacao;

import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Registro dos comprovantes já notificados (em memória, como o resto deste módulo). */
@Repository
public class NotificacaoRepository {

    private final Set<UUID> notificados = ConcurrentHashMap.newKeySet();

    public boolean jaNotificado(UUID comprovanteId) {
        return notificados.contains(comprovanteId);
    }

    public void marcarNotificado(UUID comprovanteId) {
        notificados.add(comprovanteId);
    }
}
