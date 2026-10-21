package br.com.caixa.pix.emissor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Aula 5 — o emissor publica na exchange; fila, DLX e DLQ são declaradas pelo gravador. */
@Configuration
public class MessagingConfig {

    public static final String EXCHANGE = "comprovantes.exchange";
    public static final String ROUTING = "comprovante.gravar";

    @Bean
    public DirectExchange comprovantesExchange() {
        return new DirectExchange(EXCHANGE);
    }

    /** Mensagens em JSON (usa o ObjectMapper do Boot, com suporte a LocalDateTime). */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(mapper);
    }
}
