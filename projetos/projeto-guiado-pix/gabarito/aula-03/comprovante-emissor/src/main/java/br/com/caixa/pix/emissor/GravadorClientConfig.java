package br.com.caixa.pix.emissor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * O RestClient.Builder que o GravadorClient recebe:
 * - timeout em toda chamada remota (nunca esperar para sempre);
 * - JSON em camelCase, que é o contrato HTTP do gravador (o desta API é snake_case).
 */
@Configuration
public class GravadorClientConfig {

    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder(@Value("${gravador.timeout}") Duration timeout,
                                                ObjectMapper mapper) {
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(timeout)
                .withReadTimeout(timeout);
        var camelCase = mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .messageConverters(conversores -> conversores.replaceAll(c ->
                        c instanceof MappingJackson2HttpMessageConverter
                                ? new MappingJackson2HttpMessageConverter(camelCase) : c));
    }
}
