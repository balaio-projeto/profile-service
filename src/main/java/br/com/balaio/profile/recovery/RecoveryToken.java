package br.com.balaio.profile.recovery;

import br.com.balaio.profile.account.Account;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_recovery")
public class RecoveryToken {
    @Id public UUID id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "account_id") public Account account;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) public String tokenHash;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "expires_at", nullable = false) public Instant expiresAt;
    @Column(name = "consumed_at") public Instant consumedAt;

    protected RecoveryToken() {}
    public RecoveryToken(Account account, String tokenHash, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.account = account;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }
}
