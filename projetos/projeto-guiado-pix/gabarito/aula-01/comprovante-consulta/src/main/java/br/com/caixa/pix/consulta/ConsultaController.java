package br.com.caixa.pix.consulta;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Entrypoint do bounded context CONSULTA.
 *
 * Fluxo-alvo (construído ao vivo — Aula 3):
 *   1. Buscar no CACHE (Redis no plano A; Caffeine no plano B).
 *   2. Miss → buscar no banco; encontrou → popular o cache e retornar.
 *   3. Ausência real → após 3 RETENTATIVAS → 404 Not Found.
 */
@RestController
@RequestMapping("/comprovantes")
public class ConsultaController {

    private final ConsultaComprovante service;

    public ConsultaController(ConsultaComprovante service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> consultar(@PathVariable UUID id) {
        return service.buscar(id)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
