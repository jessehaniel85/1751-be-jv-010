package br.com.caixa.pix.notificacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** BI simulado: volumetria em memória (quantidade e soma dos valores). */
@Service
public class MetricaService {

    private static final Logger log = LoggerFactory.getLogger(MetricaService.class);

    private final AtomicLong quantidade = new AtomicLong();
    private final AtomicReference<BigDecimal> total = new AtomicReference<>(BigDecimal.ZERO);

    public void incrementarVolumetria(BigDecimal valor, Instant gravadoEm) {
        long n = quantidade.incrementAndGet();
        BigDecimal soma = total.accumulateAndGet(valor, BigDecimal::add);
        log.info("BI: {} comprovante(s), total {} (último gravado em {})", n, soma, gravadoEm);
    }

    public long quantidade() { return quantidade.get(); }
    public BigDecimal total() { return total.get(); }
}
