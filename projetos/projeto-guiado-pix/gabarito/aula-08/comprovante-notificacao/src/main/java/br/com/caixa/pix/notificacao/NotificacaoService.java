package br.com.caixa.pix.notificacao;

import br.com.caixa.pix.contracts.ComprovanteGravadoEvent;
import org.springframework.stereotype.Service;

/** Aula 7 — o aviso ao cliente, idempotente: a chave natural é o id do comprovante. */
@Service
public class NotificacaoService {

    private final NotificacaoRepository repository;
    private final GatewayCliente gateway;

    public NotificacaoService(NotificacaoRepository repository, GatewayCliente gateway) {
        this.repository = repository;
        this.gateway = gateway;
    }

    public void avisar(ComprovanteGravadoEvent e) {
        // chave natural: o id do comprovante. Já notificado? não repete.
        if (repository.jaNotificado(e.comprovanteId())) {
            return; // idempotente: segunda execução não tem efeito adicional
        }
        gateway.enviar(e.chavePixDestino(), e.comprovanteId()).join(); // espera o envio antes de seguir
        repository.marcarNotificado(e.comprovanteId());
    }
}
