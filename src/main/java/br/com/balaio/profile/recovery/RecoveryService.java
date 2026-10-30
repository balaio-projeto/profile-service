package br.com.balaio.profile.recovery;

import br.com.balaio.profile.account.Account;
import br.com.balaio.profile.account.AccountRepository;
import br.com.balaio.profile.account.AccountSessionRepository;
import br.com.balaio.profile.account.AuthController;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;

@Service
public class RecoveryService {
    private final AccountRepository accounts;
    private final RecoveryTokenRepository tokens;
    private final AccountSessionRepository sessions;
    private final PasswordEncoder passwords;
    private final SecureRandom random;
    private final RecoveryConfiguration.Properties properties;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock = Clock.systemUTC();

    public RecoveryService(AccountRepository accounts, RecoveryTokenRepository tokens, AccountSessionRepository sessions, PasswordEncoder passwords,
                           SecureRandom random, RecoveryConfiguration.Properties properties, ApplicationEventPublisher events,
                           EntityManager entityManager) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.sessions = sessions;
        this.passwords = passwords;
        this.random = random;
        this.properties = properties;
        this.events = events;
        this.entityManager = entityManager;
    }

    @Transactional
    public void request(String email) {
        // Generate a token for both cases to keep the local work similar.
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String hash = hash(token);
        Optional<Account> account = accounts.findByEmail(AuthController.normalize(email));
        Instant now = clock.instant();
        UUID accountId = account.map(Account::getId).orElse(new UUID(0, 0));
        if (account.isPresent()) account = accounts.findLockedById(accountId);
        boolean recent = tokens.existsByAccountIdAndCreatedAtAfter(accountId, now.minus(Duration.ofMinutes(1)));
        if (account.isEmpty() || recent) return;
        tokens.consumeAllForAccount(accountId, now);
        tokens.save(new RecoveryToken(account.get(), hash, now, now.plus(properties.tokenTtl())));
        events.publishEvent(new RecoveryMailSender.MailRequested(account.get().email, token));
    }

    @Transactional
    public boolean reset(String token, String newPassword) {
        String tokenHash = hash(token);
        RecoveryToken recovery = tokens.findByTokenHash(tokenHash).orElse(null);
        if (recovery == null) return false;
        UUID accountId = recovery.account.getId();
        accounts.findLockedById(accountId).orElseThrow();
        entityManager.refresh(recovery);
        if (recovery.consumedAt != null || !clock.instant().isBefore(recovery.expiresAt)) return false;
        if (accounts.updatePassword(accountId, passwords.encode(newPassword)) != 1) throw new IllegalStateException("Conta de recuperação ausente");
        recovery.consumedAt = clock.instant();
        tokens.saveAndFlush(recovery);
        tokens.consumeAllForAccount(accountId, recovery.consumedAt);
        sessions.deleteAllForAccount(accountId);
        return true;
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
