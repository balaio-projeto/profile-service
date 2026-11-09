package br.com.balaio.profile.recovery;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_recovery")
public class RecoveryToken {
    @Id public UUID id;
    @Column(name = "conta_id", nullable = false) public UUID accountId;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) public String tokenHash;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "expires_at", nullable = false) public Instant expiresAt;
    @Column(name = "consumed_at") public Instant consumedAt;

    protected RecoveryToken() {}
    public RecoveryToken(UUID accountId, String tokenHash, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.accountId = accountId;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }
}
