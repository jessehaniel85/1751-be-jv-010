package br.com.caixa.pix.emissor;

import br.com.caixa.pix.contracts.ComprovanteRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Bounded context EMISSÃO — os passos locais da SAGA (Aula 2):
 * registrar (ACEITO), confirmar (GRAVADO) e a compensação (INVALIDADO).
 */
@Service
public class EmissaoService {

    private final EmissaoRegistro registro;

    public EmissaoService(EmissaoRegistro registro) {
        this.registro = registro;
    }

    public UUID registrar(ComprovanteRequest req) {
        validar(req);
        UUID id = UUID.randomUUID();
        registro.aceito(id);
        return id;
    }

    public void gravado(UUID id) {
        registro.gravado(id);
    }

    /** Compensação: marca INVALIDADO com o motivo, deixando rastro. Repetir não muda nada. */
    public void invalidar(UUID id, String motivo) {
        registro.invalidado(id, motivo);
    }

    private void validar(ComprovanteRequest r) {
        exigir(r.nome(), "nome");
        exigir(r.numeroDocumento(), "numero_documento");
        if (r.tipoDocumento() == null) throw new ComprovanteInvalidoException("tipo_documento é obrigatório");
        tamanho(r.numeroAgencia(), 4, "numero_agencia");
        tamanho(r.numeroConta(), 5, "numero_conta");
        tamanho(r.digitoVerificadorConta(), 1, "digito_verificador_conta");
        if (r.valorTransacao() == null || r.valorTransacao().compareTo(BigDecimal.ZERO) <= 0)
            throw new ComprovanteInvalidoException("valor_transacao deve ser maior que zero");
        if (r.tipoChavePixDestino() == null) throw new ComprovanteInvalidoException("tipo_chave_pix_destino é obrigatório");
        exigir(r.chavePixDestino(), "chave_pix_destino");
    }

    private void exigir(String v, String c) {
        if (v == null || v.isBlank()) throw new ComprovanteInvalidoException(c + " é obrigatório");
    }

    private void tamanho(String v, int n, String c) {
        if (v == null || v.length() != n) throw new ComprovanteInvalidoException(c + " deve ter " + n + " caractere(s)");
    }
}
