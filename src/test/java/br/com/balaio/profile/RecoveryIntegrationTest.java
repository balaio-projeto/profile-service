package br.com.balaio.profile;

import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.recovery.RecoveryMailSender;
import br.com.balaio.profile.recovery.RecoveryService;
import br.com.balaio.profile.recovery.RecoveryToken;
import br.com.balaio.profile.recovery.RecoveryTokenRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.timeout;
import org.mockito.ArgumentCaptor;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgreSQLTestConfiguration.class, RecoveryIntegrationTest.MailCapture.class})
class RecoveryIntegrationTest {
    private static final String EMAIL = "recovery.integration@example.test";
    private static final String OLD = "OldPassword123!";
    private static final String NEW = "NewPassword123!";
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecoveryTokenRepository tokens;
    @Autowired RecoveryService recovery;
    @Autowired MailCapture mail;
    @MockitoBean(enforceOverride = true) InicializarAdministradorUseCase adminBootstrap;
    @MockitoBean JavaMailSender sender;

    @TestConfiguration(proxyBeanMethods = false)
    static class MailCapture {
        final ConcurrentLinkedQueue<RecoveryMailSender.MailRequested> sent = new ConcurrentLinkedQueue<>();
        @EventListener void on(RecoveryMailSender.MailRequested event) { sent.add(event); }
    }

    @BeforeEach @AfterEach void clean() {
        mail.sent.clear();
        jdbc.update("delete from password_recovery");
        jdbc.update("delete from contas");
        jdbc.update("update controle_administracao set bootstrap_concluido=false where id=1");
    }

    @Test void requestIsUniformAndPersistsHashOnly() throws Exception {
        signup();
        var known = post("/auth/recuperacao", "{\"email\":\"  RECOVERY.INTEGRATION@EXAMPLE.TEST  \"}");
        var unknown = post("/auth/recuperacao", "{\"email\":\"unknown@example.test\"}");
        assertThat(known.statusCode()).isEqualTo(201);
        assertThat(unknown.statusCode()).isEqualTo(201);
        assertThat(known.body()).isEqualTo(unknown.body()).contains("\"recebida\":true");
        assertThat(mail.sent).hasSize(1);
        String token = mail.sent.element().token();
        String stored = jdbc.queryForObject("select token_hash from password_recovery", String.class);
        assertThat(stored).isEqualTo(RecoveryService.hash(token)).doesNotContain(token);
        assertThat(jdbc.queryForObject("select count(*) from password_recovery", Long.class)).isEqualTo(1);
        assertThat(mail.sent.element().toString()).doesNotContain(token, EMAIL);
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, timeout(5000)).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactly(EMAIL);
        assertThat(message.getValue().getText()).contains("/login/reset?token=" + token);
    }

    @Test void resetChangesExistingCredentialAndRejectsReuse() throws Exception {
        signup();
        post("/auth/recuperacao", "{\"email\":\"" + EMAIL + "\"}");
        String token = mail.sent.element().token();
        assertThat(post("/auth/redefinicao", resetBody(token, NEW)).statusCode()).isEqualTo(200);
        assertThat(post("/auth/login", credentials(OLD)).statusCode()).isEqualTo(401);
        assertThat(post("/auth/login", credentials(NEW)).statusCode()).isEqualTo(200);
        assertThat(post("/auth/redefinicao", resetBody(token, "AnotherPassword123!")).body()).contains("TOKEN_INVALIDO");
    }

    @Test void expiredUnknownAndMissingTokensAreRejected() throws Exception {
        UUID id = signup();
        tokens.save(new RecoveryToken(id, RecoveryService.hash("expired-token"), Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600)));
        assertThat(post("/auth/redefinicao", resetBody("expired-token", NEW)).body()).contains("TOKEN_INVALIDO");
        assertThat(post("/auth/redefinicao", resetBody("unknown-token", NEW)).body()).contains("TOKEN_INVALIDO");
        assertThat(post("/auth/redefinicao", "{\"novaSenha\":\"" + NEW + "\"}").body()).contains("TOKEN_INVALIDO");
        assertThat(post("/auth/redefinicao", resetBody("unknown-token", "short")).body()).contains("REQUISICAO_INVALIDA");
        assertThat(post("/auth/login", credentials(OLD)).statusCode()).isEqualTo(200);
    }

    @Test void concurrentRequestsProduceOneLink() throws Exception {
        signup();
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); recovery.request(EMAIL); return true; });
            var b = pool.submit(() -> { start.await(); recovery.request(EMAIL); return true; });
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(mail.sent).hasSize(1);
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test void concurrentTokensChangePasswordOnlyOnce() throws Exception {
        UUID id = signup();
        tokens.save(new RecoveryToken(id, RecoveryService.hash("token-a"), Instant.now(), Instant.now().plusSeconds(3600)));
        tokens.save(new RecoveryToken(id, RecoveryService.hash("token-b"), Instant.now(), Instant.now().plusSeconds(3600)));
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return recovery.reset("token-a", "FirstPassword123!"); });
            var b = pool.submit(() -> { start.await(); return recovery.reset("token-b", "SecondPassword123!"); });
            start.countDown();
            assertThat((a.get(10, TimeUnit.SECONDS) ? 1 : 0) + (b.get(10, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        }
        assertThat(tokens.findAll()).allMatch(t -> t.consumedAt != null);
    }

    private UUID signup() throws Exception {
        var result = post("/auth/cadastro", "{\"nome\":\"Recovery Test\",\"email\":\"" + EMAIL
                + "\",\"senha\":\"" + OLD + "\",\"perfil\":\"comprador\"}");
        assertThat(result.statusCode()).isEqualTo(201);
        return jdbc.queryForObject("select id from contas where email=?", UUID.class, EMAIL);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String credentials(String password) { return "{\"email\":\"" + EMAIL + "\",\"senha\":\"" + password + "\"}"; }
    private static String resetBody(String token, String password) { return "{\"token\":\"" + token + "\",\"novaSenha\":\"" + password + "\"}"; }
}
