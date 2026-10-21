package br.com.caixa.pix.notificacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Aula 6 — o aviso ao cliente (simulado no log; o gateway real entra na Aula 7). */
@Service
public class NotificacaoService {

    private static final Logger log = LoggerFactory.getLogger(NotificacaoService.class);

    public void avisarCliente(UUID comprovanteId, String chavePixDestino) {
        log.info("Enviando notificação do comprovante {} para {}", comprovanteId, chavePixDestino);
    }
}
