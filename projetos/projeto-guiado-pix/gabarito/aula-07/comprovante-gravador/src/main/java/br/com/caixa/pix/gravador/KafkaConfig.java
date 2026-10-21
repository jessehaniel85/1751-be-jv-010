package br.com.caixa.pix.gravador;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/** Aula 6 — tópico de eventos de domínio. */
@Configuration
public class KafkaConfig {

    public static final String TOPICO = "comprovante-gravado";

    @Bean
    public NewTopic comprovanteGravadoTopic() {
        return TopicBuilder.name(TOPICO).partitions(3).replicas(1).build();
    }

    /** O evento sai em JSON com o ObjectMapper do Boot: datas em ISO-8601, não em número. */
    @Bean
    @SuppressWarnings("unchecked")
    public DefaultKafkaProducerFactoryCustomizer eventoEmJson(ObjectMapper mapper) {
        return fabrica -> ((DefaultKafkaProducerFactory<Object, Object>) fabrica)
                .setValueSerializer(new JsonSerializer<>(mapper));
    }
}
