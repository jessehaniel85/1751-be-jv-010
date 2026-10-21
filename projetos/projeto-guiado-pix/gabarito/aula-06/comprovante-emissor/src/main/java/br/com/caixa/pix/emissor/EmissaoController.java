package br.com.caixa.pix.emissor;

import br.com.caixa.pix.contracts.ComprovanteAceito;
import br.com.caixa.pix.contracts.ComprovanteRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

/** Entrypoint do bounded context EMISSÃO. Aula 4: valida, delega e responde 202 sem esperar a gravação. */
@RestController
@RequestMapping("/comprovantes")
public class EmissaoController {

    private final EmissorDeComprovante emissor;

    public EmissaoController(EmissorDeComprovante emissor) {
        this.emissor = emissor;
    }

    @PostMapping
    public ResponseEntity<ComprovanteAceito> emitir(@RequestBody ComprovanteRequest req) {
        UUID id = emissor.aceitarEPublicar(req); // valida + publica o comando
        return ResponseEntity
                .accepted() // 202: "recebi, vou processar"
                .body(new ComprovanteAceito(id, LocalDateTime.now()));
    }
}
