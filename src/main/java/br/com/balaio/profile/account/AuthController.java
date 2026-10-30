package br.com.balaio.profile.account;

import br.com.balaio.profile.recovery.ApiResponse;
import br.com.balaio.profile.recovery.RecoveryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.UUID;
import java.util.Base64;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final AccountSessionRepository sessions;
    private final SecureRandom random;

    public AuthController(AccountRepository accounts, PasswordEncoder passwords, AccountSessionRepository sessions, SecureRandom random) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.sessions = sessions;
        this.random = random;
    }

    public record Signup(@NotBlank String nome, @Email @NotBlank String email,
                         @NotBlank @Size(min = 8) String senha, String perfil) {}
    public record Login(@Email @NotBlank String email, @NotBlank String senha) {}
    public record PublicAccount(UUID id, String nome, String email, String perfil, String papel, String imagem) {
        static PublicAccount of(Account account) {
            return new PublicAccount(account.id, account.name, account.email, account.role, account.role, "/fotos/jarra-cabocla.svg");
        }
    }

    @PostMapping("/cadastro")
    public ResponseEntity<ApiResponse<PublicAccount>> signup(@Valid @RequestBody Signup request, HttpServletResponse response) {
        String email = normalize(request.email());
        if (accounts.existsByEmail(email)) return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("EMAIL_EM_USO", "Já existe uma conta com este e-mail."));
        if (!"comprador".equals(request.perfil()) && !"artesao".equals(request.perfil()))
            return ResponseEntity.badRequest().body(ApiResponse.error("REQUISICAO_INVALIDA", "Perfil inválido."));
        Account account = accounts.save(new Account(request.nome().trim(), email, passwords.encode(request.senha()), request.perfil()));
        openSession(account, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(PublicAccount.of(account)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<PublicAccount>> login(@Valid @RequestBody Login request, HttpServletResponse response) {
        Account account = accounts.findByEmail(normalize(request.email())).orElse(null);
        if (account == null || !passwords.matches(request.senha(), account.passwordHash))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("CREDENCIAIS_INVALIDAS", "E-mail ou senha inválidos."));
        openSession(account, response);
        return ResponseEntity.ok(ApiResponse.ok(PublicAccount.of(account)));
    }

    @PostMapping("/logout")
    @Transactional
    public ApiResponse<LogoutReceipt> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = cookie(request);
        if (token != null) sessions.deleteByTokenHash(RecoveryService.hash(token));
        response.addHeader("Set-Cookie", ResponseCookie.from("balaio_sessao", "").httpOnly(true).sameSite("Lax").path("/").maxAge(Duration.ZERO).build().toString());
        return ApiResponse.ok(new LogoutReceipt(true));
    }
    public record LogoutReceipt(boolean encerrada) {}

    private void openSession(Account account, HttpServletResponse response) {
        byte[] secret = new byte[32]; random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        sessions.save(new AccountSession(account, RecoveryService.hash(token), Instant.now().plus(Duration.ofDays(7))));
        response.addHeader("Set-Cookie", ResponseCookie.from("balaio_sessao", token).httpOnly(true).sameSite("Lax").path("/").maxAge(Duration.ofDays(7)).build().toString());
    }

    public Account accountFromSession(HttpServletRequest request) {
        String token = cookie(request);
        if (token == null) return null;
        return sessions.findByTokenHash(RecoveryService.hash(token))
                .filter(session -> Instant.now().isBefore(session.expiresAt))
                .map(session -> accounts.findById(session.account.getId()).orElse(null)).orElse(null);
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() != null)
            for (var cookie : request.getCookies()) if ("balaio_sessao".equals(cookie.getName())) return cookie.getValue();
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) return authorization.substring(7);
        return null;
    }

    public static String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
}
