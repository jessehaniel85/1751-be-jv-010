package br.com.caixa.pix.consulta;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Lógica de consulta com cache + fallback.
 *
 *   Aula 3 — @Cacheable("comprovantes") nesta busca; miss vai ao banco e popula o cache.
 *            Implementar as 3 retentativas antes de devolver vazio (→ 404).
 *
 * Estado atual do esqueleto: sempre vazio (404), até a Aula 3 ligar cache e fonte de dados.
 */
@Service
public class ConsultaComprovante {

    public Optional<Object> buscar(UUID id) {
        // TODO (Aula 3): 1) cache  2) miss → banco/replica  3) popular cache  4) 3 retries → vazio
        return Optional.empty();
    }
}
