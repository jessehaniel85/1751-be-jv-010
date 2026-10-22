package br.com.caixa.pix.consulta;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Cache-aside dos comprovantes (Caffeine no plano B; Redis no plano A, sem mudar quem usa). */
@Component
public class ComprovanteCache {

    private final Cache cache;

    public ComprovanteCache(CacheManager cacheManager) {
        this.cache = cacheManager.getCache("comprovantes");
    }

    public Optional<ComprovanteDTO> get(UUID id) {
        return Optional.ofNullable(cache.get(id, ComprovanteDTO.class));
    }

    public void put(UUID id, ComprovanteDTO comprovante) {
        cache.put(id, comprovante);
    }
}
