package br.com.caixa.pix.notificacao;

import br.com.caixa.pix.contracts.ComprovanteGravadoEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Aula 6 — o quarto consumidor, adicionado sem tocar no produtor.
 * groupId novo → começa do offset zero (auto-offset-reset: earliest) e "vê" toda a história já gravada.
 * Nasce desligado (pix.compliance.ativo=false) para a demo ligá-lo depois e mostrar a leitura do passado.
 */
@Component
public class ComplianceListener {

    private final ComplianceService complianceService;

    public ComplianceListener(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    @KafkaListener(topics = "comprovante-gravado", groupId = "compliance",
            autoStartup = "${pix.compliance.ativo:false}")
    public void registrar(ComprovanteGravadoEvent e) {
        complianceService.arquivar(e);
    }
}
