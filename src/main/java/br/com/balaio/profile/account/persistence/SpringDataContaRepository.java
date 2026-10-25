package br.com.balaio.profile.account.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataContaRepository extends JpaRepository<ContaJpaEntity, UUID> {
    Optional<ContaJpaEntity> findByEmail(String email);

    long countByPapel(String papel);
}
