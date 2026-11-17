package br.com.balaio.profile.artisan.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataPerfilArtesaoRepository extends JpaRepository<PerfilArtesaoJpaEntity, UUID> {
    Optional<PerfilArtesaoJpaEntity> findBySlug(String slug);
    Optional<PerfilArtesaoJpaEntity> findByContaId(UUID contaId);
}
