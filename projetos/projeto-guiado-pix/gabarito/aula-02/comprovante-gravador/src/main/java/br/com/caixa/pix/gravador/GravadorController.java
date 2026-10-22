package br.com.caixa.pix.gravador;

import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint interno do GRAVADOR (Aula 2).
 * Chamado pela orquestração do emissor (SAGA síncrona). Na Aula 4 este passo
 * deixa de ser REST e passa a ser consumo de fila.
 */
@RestController
@RequestMapping("/comprovantes")
public class GravadorController {

    private final GravadorService gravador;

    public GravadorController(GravadorService gravador) {
        this.gravador = gravador;
    }

    @PostMapping
    public ResponseEntity<Void> gravar(@RequestBody GravarComprovanteCommand command) {
        gravador.gravar(command); // idempotente
        return ResponseEntity.ok().build();
    }
}
