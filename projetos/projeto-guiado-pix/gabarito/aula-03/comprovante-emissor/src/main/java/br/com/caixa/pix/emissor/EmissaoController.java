package br.com.caixa.pix.emissor;

import br.com.caixa.pix.contracts.ComprovanteAceito;
import br.com.caixa.pix.contracts.ComprovanteRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Entrypoint do bounded context EMISSÃO. Aula 2: a emissão passa pela SAGA. */
@RestController
@RequestMapping("/comprovantes")
public class EmissaoController {

    private final EmissaoSagaOrchestrator saga;

    public EmissaoController(EmissaoSagaOrchestrator saga) {
        this.saga = saga;
    }

    @PostMapping
    public ResponseEntity<ComprovanteAceito> emitir(@RequestBody ComprovanteRequest request) {
        ComprovanteAceito aceito = saga.emitir(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(aceito);
    }
}
