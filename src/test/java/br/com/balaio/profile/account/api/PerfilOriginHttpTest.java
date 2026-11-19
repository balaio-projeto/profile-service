package br.com.balaio.profile.account.api;

import static org.assertj.core.api.Assertions.*;

import br.com.balaio.profile.AutenticacaoTestFixtures;
import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.CadastrarContaPublicaComando;
import br.com.balaio.profile.account.application.port.in.CadastrarContaPublicaUseCase;
import br.com.balaio.profile.account.security.EmissorJwtAdapter;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
class PerfilOriginHttpTest {
  @org.springframework.test.context.bean.override.mockito.MockitoBean
  br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase bootstrap;

  @LocalServerPort int port;
  @Autowired CadastrarContaPublicaUseCase cadastrar;
  @Autowired EmissorJwtAdapter emissor;
  @Autowired JdbcTemplate jdbc;
  UUID conta;
  String token;

  @BeforeEach
  void setup() {
    var c =
        cadastrar.cadastrar(
            new CadastrarContaPublicaComando(
                "Origin teste",
                UUID.randomUUID() + "@test.invalid",
                "SenhaDeTeste123!",
                "artesao"));
    conta = c.id();
    token = emissor.emitir(c);
  }

  @AfterEach
  void cleanup() {
    jdbc.update("delete from contas where id=?", conta);
  }

  HttpResponse<String> patch(String path, boolean cookie, String origin) throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(
                "PATCH", HttpRequest.BodyPublishers.ofString("{\"historia\":\"Teste de origem\"}"));
    b.header(
        cookie ? "Cookie" : "Authorization", cookie ? "balaio_sessao=" + token : "Bearer " + token);
    if (origin != null) b.header("Origin", origin);
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/artesao/me", "/api/v1/artesaos/artesao-"})
  void cookieRequiresAllowedOrigin(String base) throws Exception {
    String path = base.endsWith("-") ? base + conta : base;
    assertThat(patch(path, true, null).statusCode()).isEqualTo(403);
    assertThat(patch(path, true, "https://evil.invalid").statusCode()).isEqualTo(403);
    assertThat(patch(path, true, AutenticacaoTestFixtures.ORIGIN).statusCode()).isEqualTo(200);
    assertThat(patch(path, false, null).statusCode()).isEqualTo(200);
  }
}
