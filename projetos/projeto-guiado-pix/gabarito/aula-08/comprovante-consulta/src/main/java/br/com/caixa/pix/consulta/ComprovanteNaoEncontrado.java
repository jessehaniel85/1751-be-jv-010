package br.com.caixa.pix.consulta;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/** Ausência real depois das retentativas → HTTP 404 (e nada vai para o cache). */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ComprovanteNaoEncontrado extends RuntimeException {
    public ComprovanteNaoEncontrado(UUID id) {
        super("Comprovante " + id + " não encontrado");
    }
}
