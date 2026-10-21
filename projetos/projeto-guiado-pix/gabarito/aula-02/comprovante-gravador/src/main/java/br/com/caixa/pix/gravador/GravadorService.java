package br.com.caixa.pix.gravador;

import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import org.springframework.stereotype.Service;

/** Aula 2: IDEMPOTÊNCIA — reprocessar o mesmo id não grava duas vezes. */
@Service
public class GravadorService {

    private final ComprovanteRepository repository;

    public GravadorService(ComprovanteRepository repository) {
        this.repository = repository;
    }

    /** @return true se gravou agora; false se já existia (idempotência). */
    public boolean gravar(GravarComprovanteCommand command) {
        if (repository.existsById(command.identificadorComprovante())) {
            return false;
        }
        Comprovante comprovante = Comprovante.aPartirDe(command);
        comprovante.marcarGravado();
        repository.save(comprovante);
        return true;
    }
}
