package br.com.balaio.profile.artisan.persistence;

import br.com.balaio.profile.artisan.domain.PerfilArtesao;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "perfis_artesao")
public class PerfilArtesaoJpaEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // Apenas o UUID: a conta pertence ao modulo account deste microsservico.
    @Column(name = "conta_id", nullable = false, unique = true, updatable = false)
    private UUID contaId;

    @Column(name = "slug", nullable = false, unique = true, length = 90)
    private String slug;

    @Column(name = "nome", nullable = false, length = 240)
    private String nome;

    @Column(name = "atelie", nullable = false, length = 240)
    private String atelie;

    @Column(name = "historia", nullable = false, length = 2000)
    private String historia;

    @Column(name = "territorio", nullable = false, length = 120)
    private String territorio;

    @Column(name = "tecnica", nullable = false, length = 120)
    private String tecnica;

    @Column(name = "imagem", nullable = false, length = 1024)
    private String imagem;

    protected PerfilArtesaoJpaEntity() { }

    static PerfilArtesaoJpaEntity de(PerfilArtesao perfil) {
        var entity = new PerfilArtesaoJpaEntity();
        entity.id = perfil.id();
        entity.contaId = perfil.contaId();
        entity.slug = perfil.slug();
        entity.nome = perfil.nome();
        entity.atelie = perfil.atelie();
        entity.historia = perfil.historia();
        entity.territorio = perfil.territorio();
        entity.tecnica = perfil.tecnica();
        entity.imagem = perfil.imagem();
        return entity;
    }

    PerfilArtesao dominio() {
        return new PerfilArtesao(id, contaId, slug, nome, atelie,
                historia, territorio, tecnica, imagem);
    }
}
