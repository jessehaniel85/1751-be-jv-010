package br.com.caixa.pix.gravador;

import br.com.caixa.pix.contracts.ComprovanteView;
import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** GRAVAÇÃO: persiste (idempotente) e expõe leitura por id (fonte da verdade da consulta). */
@Service
public class GravadorService {

    private final ComprovanteRepository repository;

    public GravadorService(ComprovanteRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean gravar(GravarComprovanteCommand command) {
        if (repository.existsById(command.identificadorComprovante())) {
            return false;
        }
        Comprovante comprovante = Comprovante.aPartirDe(command);
        comprovante.marcarGravado();
        repository.save(comprovante);
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
