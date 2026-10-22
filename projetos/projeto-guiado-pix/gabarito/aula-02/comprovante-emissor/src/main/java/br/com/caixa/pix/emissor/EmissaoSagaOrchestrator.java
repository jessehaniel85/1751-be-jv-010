package br.com.caixa.pix.emissor;

import br.com.caixa.pix.contracts.ComprovanteAceito;
import br.com.caixa.pix.contracts.ComprovanteRequest;
import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/** Aula 2 — SAGA orquestrada (síncrona) com compensação explícita. */
@Service
public class EmissaoSagaOrchestrator {

    private final EmissaoService emissao;
    private final GravadorClient gravador;

    public EmissaoSagaOrchestrator(EmissaoService emissao, GravadorClient gravador) {
        this.emissao = emissao;
        this.gravador = gravador;
    }

    public ComprovanteAceito emitir(ComprovanteRequest req) {
        // Passo 1: transação local na base de Emissão
        UUID id = emissao.registrar(req);
        try {
            // Passo 2: transação local na base de Gravação (idempotente)
            gravador.gravar(new GravarComprovanteCommand(id, req));
            emissao.gravado(id);
            return new ComprovanteAceito(id, LocalDateTime.now());
        } catch (GravacaoException falha) {
            // Compensação do passo 1 — operação de negócio idempotente, não um DELETE
            emissao.invalidar(id, "gravacao falhou: " + falha.getMessage());
            throw new SagaAbortada(id, falha);
        }
    }
}
