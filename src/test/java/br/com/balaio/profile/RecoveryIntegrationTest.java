package br.com.balaio.profile;

import br.com.balaio.profile.account.Account;
import br.com.balaio.profile.account.AccountRepository;
import br.com.balaio.profile.account.AccountSessionRepository;
import br.com.balaio.profile.recovery.RecoveryToken;
import br.com.balaio.profile.recovery.RecoveryTokenRepository;
import br.com.balaio.profile.recovery.RecoveryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=${PROFILE_TEST_DB_URL:jdbc:h2:mem:recoverytest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${PROFILE_TEST_DB_USER:sa}",
    "spring.datasource.password=${PROFILE_TEST_DB_PASSWORD:}",
    "management.health.mail.enabled=false"
})
@AutoConfigureMockMvc
class RecoveryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired AccountSessionRepository sessions;
    @Autowired RecoveryTokenRepository tokens;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecoveryService recoveryService;
    @MockitoBean JavaMailSender sender;

    @BeforeEach void clean() {
        tokens.deleteAll();
        sessions.deleteAll();
        accounts.deleteAll();
    }

    private String postJson(String path, String body, int status) throws Exception {
        var response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andReturn().getResponse();
        return response.getContentAsString();
    }

    @Test void requestDoesNotRevealAccountAndStoresOnlyHash() throws Exception {
        postJson("/api/v1/auth/cadastro", "{\"nome\":\"Ana\",\"email\":\"ana@example.com\",\"senha\":\"oldPassword1\",\"perfil\":\"comprador\"}", 201);
        String existing = postJson("/api/v1/auth/recuperacao", "{\"email\":\"ana@example.com\"}", 201);
        String missing = postJson("/api/v1/auth/recuperacao", "{\"email\":\"missing@example.com\"}", 201);
        assertThat(postJson("/api/v1/auth/recuperacao", "{\"email\":\"ana@example.com\"}", 201)).isEqualTo(existing);
        assertThat(existing).isEqualTo(missing);
        assertThat(existing).contains("\"recebida\":true");
        assertThat(tokens.count()).isEqualTo(1);
        assertThat(tokens.findAll().getFirst().tokenHash).hasSize(64);
    }

    @Test void resetWorksOnceAndNewPasswordAuthenticates() throws Exception {
        postJson("/api/v1/auth/cadastro", "{\"nome\":\"Ana\",\"email\":\"ana@example.com\",\"senha\":\"oldPassword1\",\"perfil\":\"comprador\"}", 201);
        Account account = accounts.findByEmail("ana@example.com").orElseThrow();
        String token = "valid-secret";
        tokens.save(new RecoveryToken(account, sha256(token), Instant.now(), Instant.now().plusSeconds(3600)));
        String reset = postJson("/api/v1/auth/redefinicao", "{\"token\":\"valid-secret\",\"novaSenha\":\"newPassword1\"}", 200);
        assertThat(reset).contains("\"redefinida\":true");
        assertThat(passwords.matches("oldPassword1", jdbc.queryForObject("select password_hash from accounts where email = ?", String.class, "ana@example.com"))).isFalse();
        assertThat(sessions.count()).isZero();
        postJson("/api/v1/auth/redefinicao", "{\"token\":\"valid-secret\",\"novaSenha\":\"anotherPass1\"}", 400);
        postJson("/api/v1/auth/login", "{\"email\":\"ana@example.com\",\"senha\":\"oldPassword1\"}", 401);
        postJson("/api/v1/auth/login", "{\"email\":\"ana@example.com\",\"senha\":\"newPassword1\"}", 200);
    }

    @Test void expiredAndUnknownTokensDoNotChangePassword() throws Exception {
        postJson("/api/v1/auth/cadastro", "{\"nome\":\"Ana\",\"email\":\"ana@example.com\",\"senha\":\"oldPassword1\",\"perfil\":\"comprador\"}", 201);
        Account account = accounts.findByEmail("ana@example.com").orElseThrow();
        tokens.save(new RecoveryToken(account, sha256("expired"), Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600)));
        postJson("/api/v1/auth/redefinicao", "{\"token\":\"expired\",\"novaSenha\":\"newPassword1\"}", 400);
        postJson("/api/v1/auth/redefinicao", "{\"token\":\"unknown\",\"novaSenha\":\"newPassword1\"}", 400);
        postJson("/api/v1/auth/login", "{\"email\":\"ana@example.com\",\"senha\":\"oldPassword1\"}", 200);
    }

    @Test void loginSessionCanBeReadAndClosed() throws Exception {
        var signup = mvc.perform(post("/api/v1/auth/cadastro").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Ana\",\"email\":\"ana@example.com\",\"senha\":\"oldPassword1\",\"perfil\":\"comprador\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        Cookie cookie = signup.getCookie("balaio_sessao");
        assertThat(cookie).isNotNull();
        mvc.perform(get("/api/v1/usuario").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/usuario").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test void resetRevokesAnExistingSession() throws Exception {
        var signup = mvc.perform(post("/api/v1/auth/cadastro").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Ana\",\"email\":\"ana@example.com\",\"senha\":\"oldPassword1\",\"perfil\":\"comprador\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        Cookie cookie = signup.getCookie("balaio_sessao");
        assertThat(cookie).isNotNull();
        mvc.perform(get("/api/v1/usuario").cookie(cookie)).andExpect(status().isOk());
        Account account = accounts.findByEmail("ana@example.com").orElseThrow();
        tokens.save(new RecoveryToken(account, sha256("reset-session"), Instant.now(), Instant.now().plusSeconds(3600)));
        postJson("/api/v1/auth/redefinicao", "{\"token\":\"reset-session\",\"novaSenha\":\"newPassword1\"}", 200);
        mvc.perform(get("/api/v1/usuario").cookie(cookie)).andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/login", "{\"email\":\"ana@example.com\",\"senha\":\"newPassword1\"}", 200);
    }

    @Test void missingTokenUsesTokenErrorAndShortPasswordUsesValidationError() throws Exception {
        assertThat(postJson("/api/v1/auth/redefinicao", "{\"novaSenha\":\"newPassword1\"}", 400)).contains("TOKEN_INVALIDO");
        assertThat(postJson("/api/v1/auth/redefinicao", "{\"token\":\"\",\"novaSenha\":\"newPassword1\"}", 400)).contains("TOKEN_INVALIDO");
        assertThat(postJson("/api/v1/auth/redefinicao", "{\"token\":\"anything\",\"novaSenha\":\"short\"}", 400)).contains("REQUISICAO_INVALIDA");
        assertThat(postJson("/api/v1/auth/redefinicao", "{", 400)).contains("REQUISICAO_INVALIDA");
    }

    @Test void concurrentRequestsCreateOnlyOneToken() throws Exception {
        postJson("/api/v1/auth/cadastro", "{\"nome\":\"Ana\",\"email\":\"ana@example.com\",\"senha\":\"oldPassword1\",\"perfil\":\"comprador\"}", 201);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); recoveryService.request("ana@example.com"); return true; });
            var second = pool.submit(() -> { start.await(); recoveryService.request("ana@example.com"); return true; });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test void concurrentTokensCanResetOnlyOnce() throws Exception {
        postJson("/api/v1/auth/cadastro", "{\"nome\":\"Ana\",\"email\":\"ana@example.com\",\"senha\":\"oldPassword1\",\"perfil\":\"comprador\"}", 201);
        Account account = accounts.findByEmail("ana@example.com").orElseThrow();
        tokens.save(new RecoveryToken(account, sha256("first-token"), Instant.now(), Instant.now().plusSeconds(3600)));
        tokens.save(new RecoveryToken(account, sha256("second-token"), Instant.now(), Instant.now().plusSeconds(3600)));
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return recoveryService.reset("first-token", "firstPassword1"); });
            var second = pool.submit(() -> { start.await(); return recoveryService.reset("second-token", "secondPassword1"); });
            start.countDown();
            assertThat((first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        }
        assertThat(tokens.findAll()).allMatch(token -> token.consumedAt != null);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
