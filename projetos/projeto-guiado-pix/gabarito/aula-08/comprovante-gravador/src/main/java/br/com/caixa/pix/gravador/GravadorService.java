package br.com.caixa.pix.gravador;

import br.com.caixa.pix.contracts.ComprovanteGravadoEvent;
import br.com.caixa.pix.contracts.ComprovanteView;
import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** GRAVAÇÃO: persiste (idempotente), expõe leitura e publica o fato "comprovante gravado" (Aula 6). */
@Service
public class GravadorService {

    private final ComprovanteRepository repository;
    private final KafkaTemplate<String, ComprovanteGravadoEvent> kafkaTemplate;

    public GravadorService(ComprovanteRepository repository,
                           KafkaTemplate<String, ComprovanteGravadoEvent> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    public boolean gravar(GravarComprovanteCommand command) {
        if (repository.existsById(command.identificadorComprovante())) {
            return false;
        }
        Comprovante comprovante = Comprovante.aPartirDe(command);
        comprovante.marcarGravado();
        repository.save(comprovante); // fonte da verdade persistida

        var evento = new ComprovanteGravadoEvent(
                comprovante.getId(),
                comprovante.getDestino().valor(),
                comprovante.getValor().valor(),
                Instant.now());

        // chave = id do comprovante → mesma partição, ordem preservada
        kafkaTemplate.send(KafkaConfig.TOPICO, comprovante.getId().toString(), evento);
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
