package br.com.caixa.pix.emissor;

import br.com.caixa.pix.contracts.ComprovanteAceito;
import br.com.caixa.pix.contracts.ComprovanteRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entrypoint do bounded context EMISSÃO.
 *
 * Fluxo-alvo (construído ao vivo):
 *   Aula 1 — valida e responde 202 com o id gerado (estado atual deste esqueleto).
 *   Aula 2 — orquestração/idempotência (SAGA) garantindo emissão→gravação.
 *   Aula 4/5 — em vez de processar inline, PUBLICA um {@code GravarComprovanteCommand}
 *              na fila; o comprovante-gravador consome e persiste.
 */
@RestController
@RequestMapping("/comprovantes")
public class EmissaoController {

    @PostMapping
    public ResponseEntity<ComprovanteAceito> emitir(@RequestBody ComprovanteRequest request) {
        // TODO (Aula 1): validar campos obrigatórios e formatos (agência=4, conta=5, dígito=1).
        UUID id = UUID.randomUUID();

        // TODO (Aula 4/5): publicar new GravarComprovanteCommand(id, request) na fila
        //                  em vez de retornar direto. Responder 202 ANTES de gravar.

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(new ComprovanteAceito(id, LocalDateTime.now()));
    }
}
