package br.com.caixa.pix.notificacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/** Antifraude simulado: registra a análise. A regra de fraude em si não é tema do módulo. */
@Service
public class AntifraudeService {

    private static final Logger log = LoggerFactory.getLogger(AntifraudeService.class);

    public void avaliarPadrao(UUID comprovanteId, BigDecimal valor) {
        log.info("Antifraude avaliando o padrão do comprovante {} (valor {})", comprovanteId, valor);
    }
}
