package br.com.caixa.pix.emissor;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado das emissões (Aula 2). Suporte didático à SAGA:
 * a transição para INVALIDADO, com o motivo, é a AÇÃO COMPENSATÓRIA quando a gravação não conclui.
 */
@Component
public class EmissaoRegistro {

    public enum Status { ACEITO, GRAVADO, INVALIDADO }

    private final Map<UUID, Status> estados = new ConcurrentHashMap<>();
    private final Map<UUID, String> motivos = new ConcurrentHashMap<>();

    public void aceito(UUID id)  { estados.put(id, Status.ACEITO); }
    public void gravado(UUID id) { estados.put(id, Status.GRAVADO); }

    public void invalidado(UUID id, String motivo) { // compensação
        estados.put(id, Status.INVALIDADO);
        motivos.putIfAbsent(id, motivo);
    }

    public Status status(UUID id) { return estados.get(id); }
    public String motivo(UUID id) { return motivos.get(id); }
}
