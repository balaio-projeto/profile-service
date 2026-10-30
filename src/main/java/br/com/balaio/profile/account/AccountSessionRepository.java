package br.com.balaio.profile.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AccountSessionRepository extends JpaRepository<AccountSession, UUID> {
    Optional<AccountSession> findByTokenHash(String tokenHash);
    void deleteByTokenHash(String tokenHash);
    @Modifying
    @Query("delete from AccountSession s where s.account.id = :accountId")
    void deleteAllForAccount(UUID accountId);
}
