package br.com.caixa.pix.consulta;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Aula 3 — consulta com cache-aside + retentativas.
 * Fluxo: cache → (miss) gravador com até 3 retentativas e backoff → popula o cache → senão 404.
 * Cacheia a PRESENÇA (comprovante gravado, imutável), nunca a ausência transitória.
 */
@Service
public class ConsultaComprovante {

    private static final int MAX_TENTATIVAS = 3;

    private final GravadorClient gravador;
    private final ComprovanteCache cache;
    private final Duration backoffInicial;

    public ConsultaComprovante(GravadorClient gravador, ComprovanteCache cache,
                               @Value("${consulta.backoff-inicial}") Duration backoffInicial) {
        this.gravador = gravador;
        this.cache = cache;
        this.backoffInicial = backoffInicial;
    }

    public ComprovanteDTO consultar(UUID id) {
        return cache.get(id)                       // 1. tenta o cache
            .or(() -> buscarComRetentativas(id))   // 2. miss → gravador com retries
            .orElseThrow(() -> new ComprovanteNaoEncontrado(id)); // 3. 404 — não cacheado
    }

    private Optional<ComprovanteDTO> buscarComRetentativas(UUID id) {
        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS; tentativa++) {
            Optional<ComprovanteDTO> achado = gravador.buscarPorId(id);
            if (achado.isPresent()) {
                cache.put(id, achado.get());       // só popula o cache na PRESENÇA
                return achado;
            }
            esperarBackoff(tentativa);             // janela da gravação assíncrona
        }
        return Optional.empty();                    // ausência real → 404, sem cachear
    }

    /** Backoff exponencial: backoffInicial, 2x, 4x... */
    private void esperarBackoff(int tentativa) {
        try {
            Thread.sleep(backoffInicial.multipliedBy(1L << (tentativa - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
