package br.com.caixa.pix.notificacao;

import br.com.caixa.pix.contracts.ComprovanteGravadoEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Aula 6 — consumer group "bi": soma a volumetria a partir do mesmo fluxo de eventos. */
@Component
public class BiListener {

    private final MetricaService metricaService;

    public BiListener(MetricaService metricaService) {
        this.metricaService = metricaService;
    }

    @KafkaListener(topics = "comprovante-gravado", groupId = "bi")
    public void agregar(ComprovanteGravadoEvent e) {
        metricaService.incrementarVolumetria(e.valor(), e.gravadoEm());
    }
}
