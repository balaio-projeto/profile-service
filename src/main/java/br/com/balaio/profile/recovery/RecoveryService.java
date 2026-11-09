package br.com.balaio.profile.recovery;

import br.com.balaio.profile.account.application.port.out.CodificarSenhaPort;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.application.port.out.VerificarSenhaPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.PoliticaSenha;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoveryService {
    private final ContasPort contas;
    private final RecoveryTokenRepository tokens;
    private final CodificarSenhaPort codificar;
    private final VerificarSenhaPort verificar;
    private final SecureRandom random;
    private final RecoveryConfiguration.Properties properties;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock = Clock.systemUTC();

    public RecoveryService(ContasPort contas, RecoveryTokenRepository tokens, CodificarSenhaPort codificar,
            VerificarSenhaPort verificar, SecureRandom random, RecoveryConfiguration.Properties properties,
            ApplicationEventPublisher events, EntityManager entityManager) {
        this.contas = contas;
        this.tokens = tokens;
        this.codificar = codificar;
        this.verificar = verificar;
        this.random = random;
        this.properties = properties;
        this.events = events;
        this.entityManager = entityManager;
    }

    @Transactional
    public void request(String rawEmail) {
        Email email = new Email(rawEmail);
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String hash = hash(token);
        Optional<Conta> found = contas.buscarPorEmail(email);
        UUID accountId = found.map(Conta::id).orElse(new UUID(0, 0));
        if (found.isPresent()) found = contas.bloquearPorId(accountId);
        Instant now = clock.instant();
        boolean recent = tokens.existsByAccountIdAndCreatedAtAfter(accountId, now.minus(Duration.ofMinutes(1)));
        if (found.isEmpty() || recent) return;
        tokens.consumeAllForAccount(accountId, now);
        tokens.save(new RecoveryToken(accountId, hash, now, now.plus(properties.tokenTtl())));
        events.publishEvent(new RecoveryMailSender.MailRequested(email.valor(), token));
    }

    @Transactional
    public boolean reset(String token, String newPassword) {
        try {
            PoliticaSenha.validar(newPassword);
        } catch (IllegalArgumentException invalid) {
            throw new RecoveryPasswordException();
        }
        String tokenHash = hash(token);
        RecoveryToken recovery = tokens.findByTokenHash(tokenHash).orElse(null);
        if (recovery == null) return false;
        UUID accountId = recovery.accountId;
        Conta conta = contas.bloquearPorId(accountId).orElse(null);
        if (conta == null) return false;
        entityManager.refresh(recovery);
        if (recovery.consumedAt != null || !clock.instant().isBefore(recovery.expiresAt)) return false;
        if (verificar.corresponde(newPassword, conta.senhaProtegida())) throw new RecoveryPasswordException();
        var novaSenha = codificar.codificar(newPassword);
        if (!contas.atualizarSenha(accountId, conta.senhaProtegida(), novaSenha))
            throw new IllegalStateException("Falha na atualização da credencial de recuperação.");
        Instant now = clock.instant();
        recovery.consumedAt = now;
        tokens.saveAndFlush(recovery);
        tokens.consumeAllForAccount(accountId, now);
        return true;
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
