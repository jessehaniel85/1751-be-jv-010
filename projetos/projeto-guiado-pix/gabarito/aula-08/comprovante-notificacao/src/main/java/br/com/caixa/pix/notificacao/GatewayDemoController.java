package br.com.caixa.pix.notificacao;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

/**
 * Só para a demonstração em aula (perfil "demo"): controla o gateway simulado sem editar
 * código nem reiniciar o serviço, dispara rajadas concorrentes (bulkhead) e mostra os reatores.
 */
@Profile("demo")
@RestController
@RequestMapping("/demo")
public class GatewayDemoController {

    private final GatewayHttp gatewayHttp;
    private final GatewayCliente gateway;
    private final ReenvioRepository reenvios;
    private final AlertaService alertas;
    private final MetricaService metricas;
    private final ComplianceService compliance;

    public GatewayDemoController(GatewayHttp gatewayHttp, GatewayCliente gateway, ReenvioRepository reenvios,
                                 AlertaService alertas, MetricaService metricas, ComplianceService compliance) {
        this.gatewayHttp = gatewayHttp;
        this.gateway = gateway;
        this.reenvios = reenvios;
        this.alertas = alertas;
        this.metricas = metricas;
        this.compliance = compliance;
    }

    /** falhar: o gateway lança erro; propagar: o fallback devolve a falha (retry e DLT); lentidao: ms por chamada. */
    @PostMapping("/gateway")
    public String configurar(@RequestParam(defaultValue = "false") boolean falhar,
                             @RequestParam(defaultValue = "false") boolean propagar,
                             @RequestParam(defaultValue = "0") long lentidao) {
        gatewayHttp.configurar(falhar, lentidao);
        gateway.propagarFalha(propagar);
        return estado();
    }

    @GetMapping("/gateway")
    public String estado() {
        return gatewayHttp.estado() + ", propagar=" + gateway.propagaFalha();
    }

    /** n chamadas simultâneas ao gateway: com ele lento, o bulkhead recusa o que não cabe no pool + fila. */
    @PostMapping("/gateway/rajada")
    public String rajada(@RequestParam(defaultValue = "10") int n) {
        int antes = reenvios.pendentes();
        CompletableFuture<?>[] chamadas = IntStream.range(0, n)
                .mapToObj(i -> CompletableFuture.supplyAsync(() ->
                        gateway.enviar("demo@pix.com", UUID.randomUUID())).thenCompose(f -> f))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(chamadas).exceptionally(t -> null).join();
        int recusadas = reenvios.pendentes() - antes;
        return "chamadas=" + n + ", atendidas=" + (n - recusadas) + ", enviadas_para_reenvio=" + recusadas;
    }

    @GetMapping("/reatores")
    public String reatores() {
        return "bi_quantidade=" + metricas.quantidade() + ", bi_total=" + metricas.total()
                + ", compliance_arquivados=" + compliance.arquivados()
                + ", reenvios_pendentes=" + reenvios.pendentes()
                + ", falhas_permanentes=" + alertas.falhasPermanentes();
    }
}
