package br.com.caixa.pix.consulta;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** CONSULTA: GET por id com cache + fallback no gravador (Aula 3). */
@RestController
@RequestMapping("/comprovantes")
public class ConsultaController {

    private final ConsultaComprovante consulta;

    public ConsultaController(ConsultaComprovante consulta) {
        this.consulta = consulta;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ComprovanteDTO> consultar(@PathVariable UUID id) {
        return ResponseEntity.ok(consulta.consultar(id));
    }
}
