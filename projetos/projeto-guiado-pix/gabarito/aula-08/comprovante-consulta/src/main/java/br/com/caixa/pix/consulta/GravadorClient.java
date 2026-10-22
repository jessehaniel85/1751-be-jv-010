package br.com.caixa.pix.consulta;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

/** comprovante-consulta — o cliente REST que conversa com o gravador (a fonte do comprovante). */
@Component
public class GravadorClient {

    private final RestClient http;

    public GravadorClient(RestClient.Builder b, @Value("${gravador.url}") String url) {
        this.http = b.baseUrl(url).build();
    }

    public Optional<ComprovanteDTO> buscarPorId(UUID id) {
        return Optional.ofNullable(
            http.get().uri("/comprovantes/{id}", id)
                .retrieve()
                .onStatus(status -> status.value() == 404, (req, resp) -> {}) // 404 -> corpo nulo
                .body(ComprovanteDTO.class));
    }
}
