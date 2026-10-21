package br.com.caixa.pix.gravador;

import br.com.caixa.pix.contracts.ComprovanteGravadoEvent;
import br.com.caixa.pix.contracts.ComprovanteView;
import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/** GRAVAÇÃO: persiste (idempotente), expõe leitura e publica o evento de gravação. */
@Service
public class GravadorService {

    private final ComprovanteRepository repository;
    private final EventoPublisher eventos;

    public GravadorService(ComprovanteRepository repository, EventoPublisher eventos) {
        this.repository = repository;
        this.eventos = eventos;
    }

    @Transactional
    public boolean gravar(GravarComprovanteCommand command) {
        if (repository.existsById(command.identificadorComprovante())) {
            return false;
        }
        Comprovante comprovante = Comprovante.aPartirDe(command);
        comprovante.marcarGravado();
        repository.save(comprovante);
        eventos.publicar(new ComprovanteGravadoEvent(command.identificadorComprovante(), LocalDateTime.now()));
        return true;
    }

    public Optional<ComprovanteView> buscar(UUID id) {
        return repository.findById(id).map(this::toView);
    }

    private ComprovanteView toView(Comprovante c) {
        return new ComprovanteView(c.getId(), c.getValor().valor(), c.getDestino().valor(),
                c.getDataHoraTransacao(), c.getStatus().name(), c.getNome(),
                c.getNumeroDocumento(), c.getDataHoraGravacao());
    }
}
