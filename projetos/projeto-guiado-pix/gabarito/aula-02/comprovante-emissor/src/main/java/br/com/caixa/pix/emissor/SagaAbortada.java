package br.com.caixa.pix.emissor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/** A SAGA foi abortada e a emissão, compensada → HTTP 502. */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class SagaAbortada extends RuntimeException {
    public SagaAbortada(UUID id, GravacaoException causa) {
        super("Falha ao gravar o comprovante " + id + " (emissão compensada)", causa);
    }
}
