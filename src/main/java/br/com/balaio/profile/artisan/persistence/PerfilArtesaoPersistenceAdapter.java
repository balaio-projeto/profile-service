package br.com.balaio.profile.artisan.persistence;

import br.com.balaio.profile.artisan.application.PerfilArtesaoPort;
import br.com.balaio.profile.artisan.domain.PerfilArtesao;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class PerfilArtesaoPersistenceAdapter implements PerfilArtesaoPort {
    private final SpringDataPerfilArtesaoRepository repository;
    public PerfilArtesaoPersistenceAdapter(SpringDataPerfilArtesaoRepository repository) {
        this.repository = repository;
    }
    public List<PerfilArtesao> listar() {
        return repository.findAll().stream().map(PerfilArtesaoJpaEntity::dominio).toList();
    }
    public Optional<PerfilArtesao> buscarPorSlug(String slug) {
        return repository.findBySlug(slug).map(PerfilArtesaoJpaEntity::dominio);
    }
    public Optional<PerfilArtesao> buscarPorContaId(UUID contaId) {
        return repository.findByContaId(contaId).map(PerfilArtesaoJpaEntity::dominio);
    }
    public PerfilArtesao salvar(PerfilArtesao perfil) {
        return repository.save(PerfilArtesaoJpaEntity.de(perfil)).dominio();
    }
}
