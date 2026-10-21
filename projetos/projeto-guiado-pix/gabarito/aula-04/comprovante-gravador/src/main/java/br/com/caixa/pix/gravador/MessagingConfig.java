package br.com.caixa.pix.gravador;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Aula 4 — exchange direct → fila de gravação (a DLQ entra na Aula 5). */
@Configuration
public class MessagingConfig {

    public static final String EXCHANGE = "comprovantes.exchange";
    public static final String ROUTING = "comprovante.gravar";
    public static final String FILA_GRAVACAO = "gravacao.q";

    @Bean
    public DirectExchange comprovantesExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Queue gravacaoQueue() {
        return new Queue(FILA_GRAVACAO, true);
    }

    @Bean
    public Binding gravacaoBinding() {
        return BindingBuilder.bind(gravacaoQueue()).to(comprovantesExchange()).with(ROUTING);
    }

    /**
     * O emissor publica o comando em snake_case (Jackson global dele). O HTTP do gravador
     * segue em camelCase (é o que a consulta lê), então só a fila lê em snake_case.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(
                mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }
}
