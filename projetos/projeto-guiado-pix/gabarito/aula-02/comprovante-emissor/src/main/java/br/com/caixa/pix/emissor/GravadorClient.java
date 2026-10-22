package br.com.caixa.pix.emissor;

import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Passo remoto da SAGA (Aula 2): chama o gravador via REST. Vira fila na Aula 4. */
@Component
public class GravadorClient {

    private final RestClient http;

    public GravadorClient(RestClient.Builder b, @Value("${gravador.url}") String url) {
        this.http = b.baseUrl(url).build();
    }

    /** Lança GravacaoException se o gravador falhar ou não responder (dispara a compensação). */
    public void gravar(GravarComprovanteCommand command) {
        try {
            http.post().uri("/comprovantes").body(command).retrieve().toBodilessEntity();
        } catch (RestClientException falha) {
            throw new GravacaoException(falha.getMessage(), falha);
        }
    }
}
