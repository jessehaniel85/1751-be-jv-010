package br.com.caixa.pix.gravador;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Aula 5 — topologia formal: exchange direct -> fila de gravacao, com DEAD LETTER.
 * Falha transitoria: retry (config no application.yml). Falha permanente: vai para a DLQ.
 */
@Configuration
public class MessagingConfig {

    public static final String EXCHANGE   = "gravacao.ex";
    public static final String ROUTING    = "gravacao";
    public static final String FILA        = "gravacao.q";
    public static final String DLX        = "gravacao.dlx";
    public static final String DLQ        = "gravacao.dlq";

    @Bean
    public DirectExchange gravacaoExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Queue gravacaoQueue() {
        return QueueBuilder.durable(FILA)
                .withArgument("x-dead-letter-exchange", DLX)
                .build();
    }

    @Bean
    public Binding gravacaoBinding() {
        return BindingBuilder.bind(gravacaoQueue()).to(gravacaoExchange()).with(ROUTING);
    }

    @Bean
    public FanoutExchange deadLetterExchange() {
        return new FanoutExchange(DLX);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange());
    }

    /**
     * O emissor publica o comando em snake_case (Jackson global dele). O HTTP do gravador
     * segue em camelCase (contrato PACT com a consulta), então só a fila lê em snake_case.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(
                mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }
}
