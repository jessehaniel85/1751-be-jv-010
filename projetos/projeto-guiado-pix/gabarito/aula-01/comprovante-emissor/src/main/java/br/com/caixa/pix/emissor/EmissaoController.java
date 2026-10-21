package br.com.caixa.pix.emissor;

import br.com.caixa.pix.contracts.ComprovanteAceito;
import br.com.caixa.pix.contracts.ComprovanteRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrypoint do bounded context EMISSÃO.
 * Aula 1: valida e responde 202 com o id gerado.
 */
@RestController
@RequestMapping("/comprovantes")
public class EmissaoController {

    private final EmissaoService emissao;

    public EmissaoController(EmissaoService emissao) {
        this.emissao = emissao;
    }

    @PostMapping
    public ResponseEntity<ComprovanteAceito> emitir(@RequestBody ComprovanteRequest request) {
        ComprovanteAceito aceito = emissao.aceitar(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(aceito);
    }
}
