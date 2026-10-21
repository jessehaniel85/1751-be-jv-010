package br.com.caixa.pix.emissor;

/** O passo remoto da SAGA (gravar no gravador) não concluiu. */
public class GravacaoException extends RuntimeException {
    public GravacaoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
