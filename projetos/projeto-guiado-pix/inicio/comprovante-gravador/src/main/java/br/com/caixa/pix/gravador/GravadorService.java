package br.com.caixa.pix.gravador;

import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import org.springframework.stereotype.Service;

/**
 * Núcleo do bounded context GRAVAÇÃO.
 *
 * Fluxo-alvo (construído ao vivo):
 *   Aula 4/5 — invocado por um @RabbitListener ao consumir a fila de gravação.
 *   Aula 5   — IDEMPOTÊNCIA: se o id já existe, NÃO grava de novo (reprocesso seguro).
 *   Aula 6   — após gravar, PUBLICA ComprovanteGravadoEvent no tópico Kafka.
 *   Aula 7   — resiliência (retry/DLQ) no consumo.
 */
@Service
public class GravadorService {

    private final ComprovanteRepository repository;

    public GravadorService(ComprovanteRepository repository) {
        this.repository = repository;
    }

    public void gravar(GravarComprovanteCommand command) {
        // TODO (Aula 5): idempotência — if (repository.existsById(command.identificadorComprovante())) return;
        // TODO (Aula 4): Comprovante.aPartirDe(command), marcarGravado() e persistir.
        // TODO (Aula 6): publicar ComprovanteGravadoEvent no tópico.
        throw new UnsupportedOperationException("Construído na Aula 4/5");
    }
}
