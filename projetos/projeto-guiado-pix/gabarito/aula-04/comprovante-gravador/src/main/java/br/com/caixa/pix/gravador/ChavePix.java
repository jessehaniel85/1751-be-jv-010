package br.com.caixa.pix.gravador;

import br.com.caixa.pix.contracts.TipoChave;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/** Value object: sem identidade, imutável, validado no construtor (Aula 1). */
@Embeddable
public record ChavePix(@Enumerated(EnumType.STRING) TipoChave tipo, String valor) {
    public ChavePix {
        if (valor == null || valor.isBlank())
            throw new IllegalArgumentException("chave PIX vazia");
    }
}
