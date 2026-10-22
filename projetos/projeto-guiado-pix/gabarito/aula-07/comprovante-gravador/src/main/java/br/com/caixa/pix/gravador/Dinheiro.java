package br.com.caixa.pix.gravador;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/** Value object: igualdade por valor; operações devolvem um NOVO objeto (Aula 1). */
@Embeddable
public record Dinheiro(BigDecimal valor, String moeda) {
    public Dinheiro {
        if (valor.signum() < 0) throw new IllegalArgumentException("valor negativo");
    }

    public Dinheiro soma(Dinheiro outro) {
        if (!moeda.equals(outro.moeda)) throw new IllegalArgumentException("moedas diferentes");
        return new Dinheiro(valor.add(outro.valor), moeda);
    }
}
