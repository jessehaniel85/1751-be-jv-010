package br.com.caixa.pix.notificacao;

import br.com.caixa.pix.contracts.ComprovanteGravadoEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Compliance simulado: arquiva os eventos em memória. */
@Service
public class ComplianceService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceService.class);

    private final List<ComprovanteGravadoEvent> arquivo = new CopyOnWriteArrayList<>();

    public void arquivar(ComprovanteGravadoEvent e) {
        arquivo.add(e);
        log.info("Compliance arquivou o comprovante {} ({} no arquivo)", e.comprovanteId(), arquivo.size());
    }

    public int arquivados() { return arquivo.size(); }
}
