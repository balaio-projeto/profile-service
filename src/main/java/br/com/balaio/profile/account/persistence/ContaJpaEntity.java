package br.com.balaio.profile.account.persistence;

import java.util.UUID;

import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.SenhaProtegida;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "contas")
public class ContaJpaEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 240)
    private String nome;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "senha_hash", nullable = false, length = 255)
    private String senhaHash;

    @Column(nullable = false, length = 16)
    private String papel;

    @Column(name = "troca_senha_obrigatoria", nullable = false)
    private boolean trocaSenhaObrigatoria;

    protected ContaJpaEntity() {
    }

    public static ContaJpaEntity fromDomain(Conta conta) {
        var entity = new ContaJpaEntity();
        entity.id = conta.id();
        entity.nome = conta.nome();
        entity.email = conta.email().valor();
        entity.senhaHash = conta.senhaProtegida().valor();
        entity.papel = conta.papel().codigo();
        entity.trocaSenhaObrigatoria = conta.trocaSenhaObrigatoria();
        return entity;
    }

    public Conta toDomain() {
        return new Conta(id, nome, new Email(email), new SenhaProtegida(senhaHash),
                Papel.fromCodigo(papel), trocaSenhaObrigatoria);
    }
}
