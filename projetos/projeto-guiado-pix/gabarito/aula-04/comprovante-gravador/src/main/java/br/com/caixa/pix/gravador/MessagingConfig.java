package br.com.caixa.pix.gravador;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MessagingConfig {

    public static final String FILA_GRAVACAO = "gravacao.q";

    @Bean
    public Queue gravacaoQueue() {
        return new Queue(FILA_GRAVACAO, true);
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
