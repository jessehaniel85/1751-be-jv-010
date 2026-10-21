package br.com.caixa.pix.notificacao;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Só para a demonstração em aula (perfil "demo"): liga e desliga a falha do gateway
 * sem editar código nem reiniciar o serviço.
 */
@Profile("demo")
@RestController
@RequestMapping("/demo/gateway")
public class GatewayDemoController {

    private final NotificacaoGateway gateway;

    public GatewayDemoController(NotificacaoGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping
    public String configurar(@RequestParam(defaultValue = "false") boolean falhar,
                             @RequestParam(defaultValue = "false") boolean propagar) {
        gateway.configurar(falhar, propagar);
        return gateway.estado();
    }

    @GetMapping
    public String estado() {
        return gateway.estado();
    }
}
