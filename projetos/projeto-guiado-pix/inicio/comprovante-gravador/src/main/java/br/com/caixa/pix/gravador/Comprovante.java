package br.com.caixa.pix.gravador;

import br.com.caixa.pix.contracts.ComprovanteRequest;
import br.com.caixa.pix.contracts.GravarComprovanteCommand;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Raiz do agregado Comprovante no bounded context GRAVAÇÃO (Aula 1 — DDD).
 * Fronteira do agregado: { Comprovante, ChavePix, Dinheiro }. Invariante: o status só avança
 * de ACEITO para GRAVADO. Base SEGREGADA deste serviço (não compartilhar tabela com outros serviços).
 */
@Entity
public class Comprovante {

    @Id
    private UUID id; // = identificadorComprovante (chave de idempotência)

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "tipo", column = @Column(name = "tipo_chave_destino")),
            @AttributeOverride(name = "valor", column = @Column(name = "chave_destino"))
    })
    private ChavePix destino;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "valor", column = @Column(name = "valor")),
            @AttributeOverride(name = "moeda", column = @Column(name = "moeda"))
    })
    private Dinheiro valor;

    @Enumerated(EnumType.STRING)
    private StatusComprovante status;

    private String nome;
    private String numeroDocumento;
    private LocalDateTime dataHoraTransacao;
    private LocalDateTime dataHoraGravacao;

    protected Comprovante() {
        // exigido pelo JPA
    }

    public Comprovante(UUID id, ChavePix destino, Dinheiro valor) {
        this.id = id;
        this.destino = destino;
        this.valor = valor;
        this.status = StatusComprovante.ACEITO;
    }

    /** Monta o comprovante (ainda ACEITO) a partir do comando de gravação. */
    public static Comprovante aPartirDe(GravarComprovanteCommand cmd) {
        ComprovanteRequest dados = cmd.dados();
        Comprovante c = new Comprovante(cmd.identificadorComprovante(),
                new ChavePix(dados.tipoChavePixDestino(), dados.chavePixDestino()),
                new Dinheiro(dados.valorTransacao(), "BRL"));
        c.nome = dados.nome();
        c.numeroDocumento = dados.numeroDocumento();
        c.dataHoraTransacao = dados.dataHoraTransacao();
        return c;
    }

    /** Única forma de mudar o estado. */
    public void marcarGravado() {
        if (status != StatusComprovante.ACEITO)
            throw new IllegalStateException("só ACEITO pode ir para GRAVADO");
        this.status = StatusComprovante.GRAVADO;
        this.dataHoraGravacao = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public ChavePix getDestino() { return destino; }
    public Dinheiro getValor() { return valor; }
    public StatusComprovante getStatus() { return status; }
    public String getNome() { return nome; }
    public String getNumeroDocumento() { return numeroDocumento; }
    public LocalDateTime getDataHoraTransacao() { return dataHoraTransacao; }
    public LocalDateTime getDataHoraGravacao() { return dataHoraGravacao; }

    /** Entidade: igualdade SOMENTE por identidade. */
    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Comprovante outro && Objects.equals(id, outro.id));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
