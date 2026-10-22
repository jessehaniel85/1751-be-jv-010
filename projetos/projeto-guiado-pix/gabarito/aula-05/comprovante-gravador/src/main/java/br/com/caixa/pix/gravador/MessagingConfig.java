package br.com.caixa.pix.gravador;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.Map;

/**
 * Aula 5 — topologia formal: exchange direct → fila de gravação, com DEAD LETTER.
 * Falha transitória: retry com backoff (interceptor). Falha permanente ou retry esgotado: DLQ.
 */
@Configuration
public class MessagingConfig {

    public static final String EXCHANGE = "comprovantes.exchange";
    public static final String ROUTING = "comprovante.gravar";
    public static final String FILA = "gravacao.q";
    public static final String DLX = "gravacao.dlx";
    public static final String ROUTING_MORTO = "comprovante.morto";
    public static final String DLQ = "gravacao.dlq";

    @Bean
    public DirectExchange comprovantesExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Queue gravacaoQueue() {
        return QueueBuilder.durable(FILA)
                .withArgument("x-dead-letter-exchange", DLX)              // para onde mandar
                .withArgument("x-dead-letter-routing-key", ROUTING_MORTO) // com que rótulo
                .build();
    }

    @Bean
    public Binding gravacaoBinding() {
        return BindingBuilder.bind(gravacaoQueue()).to(comprovantesExchange()).with(ROUTING);
    }

    @Bean
    public Queue dlq() {
        return QueueBuilder.durable(DLQ).build(); // a fila dos mortos
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX);
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(dlq()).to(deadLetterExchange()).with(ROUTING_MORTO); // rota da DLX até a DLQ
    }

    @Bean
    public RetryOperationsInterceptor retryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .retryPolicy(new SimpleRetryPolicy(4,             // 1 original + 3 retries
                        Map.of(AmqpRejectAndDontRequeueException.class, false,  // permanente: sem retry
                               MessageConversionException.class, false),         // corpo ilegível também
                        true, true))
                .backOffOptions(1000, 2.0, 10000)               // 1s, 2s, 4s... teto 10s
                .recoverer(new RejectAndDontRequeueRecoverer()) // esgotou → dead-letter
                .build();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setPrefetchCount(10);   // no máx. 10 mensagens in-flight por consumidor
        factory.setDefaultRequeueRejected(false); // exceção → dead-letter, não requeue infinito
        factory.setAdviceChain(retryInterceptor());
        return factory;
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
