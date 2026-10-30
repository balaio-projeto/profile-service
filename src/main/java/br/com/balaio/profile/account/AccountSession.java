package br.com.balaio.profile.account;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_sessions")
public class AccountSession {
    @Id public UUID id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "account_id") public Account account;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) public String tokenHash;
    @Column(name = "expires_at", nullable = false) public Instant expiresAt;
    protected AccountSession() {}
    AccountSession(Account account, String tokenHash, Instant expiresAt) {
        this.id = UUID.randomUUID(); this.account = account; this.tokenHash = tokenHash; this.expiresAt = expiresAt;
    }
}
