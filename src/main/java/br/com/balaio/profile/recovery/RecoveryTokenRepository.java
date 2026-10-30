package br.com.balaio.profile.recovery;

import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RecoveryTokenRepository extends JpaRepository<RecoveryToken, UUID> {
    Optional<RecoveryToken> findByTokenHash(String tokenHash);
    boolean existsByAccountIdAndCreatedAtAfter(UUID accountId, Instant createdAt);

    @Modifying
    @Query("update RecoveryToken t set t.consumedAt = :now where t.account.id = :accountId and t.consumedAt is null")
    void consumeAllForAccount(UUID accountId, Instant now);
}
