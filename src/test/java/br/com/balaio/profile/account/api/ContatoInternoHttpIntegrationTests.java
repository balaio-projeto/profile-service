package br.com.balaio.profile.account.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.CadastrarContaPublicaComando;
import br.com.balaio.profile.account.application.port.in.CadastrarContaPublicaUseCase;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "balaio.internal-token=token-interno-de-teste-1234567890ab")
@Import(PostgreSQLTestConfiguration.class)
class ContatoInternoHttpIntegrationTests {
    private static final String TOKEN = "token-interno-de-teste-1234567890ab";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int porta;
    @MockitoBean(enforceOverride = true)
    private InicializarAdministradorUseCase inicializarAdministrador;
    @Autowired
    private CadastrarContaPublicaUseCase cadastrar;
    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @AfterEach
    void limpar() {
        jdbc.update("delete from contas where email like 'contato-interno-%@example.test'");
    }

    @Test
    void devolveNomeEEmailSomenteComTokenInterno() throws Exception {
        var conta = cadastrar.cadastrar(new CadastrarContaPublicaComando("Pessoa Compradora",
                "contato-interno-" + UUID.randomUUID() + "@example.test", "Senha descartável de integração", "comprador"));
        String caminho = "/internal/v1/contas/" + conta.id() + "/contato";

        var semToken = consultar(caminho, null);
        var tokenErrado = consultar(caminho, "token-incorreto-de-teste-1234567890ab");
        var autorizado = consultar(caminho, TOKEN);
        var inexistente = consultar("/internal/v1/contas/" + UUID.randomUUID() + "/contato", TOKEN);

        assertThat(semToken.statusCode()).isEqualTo(403);
        assertThat(tokenErrado.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(tokenErrado.body()).path("erro").path("codigo").asString()).isEqualTo("SEM_PERMISSAO");
        assertThat(autorizado.statusCode()).isEqualTo(200);
        assertThat(autorizado.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode dados = JSON.readTree(autorizado.body()).path("dados");
        assertThat(dados.path("id").asString()).isEqualTo(conta.id().toString());
        assertThat(dados.path("nome").asString()).isEqualTo("Pessoa Compradora");
        assertThat(dados.path("email").asString()).isEqualTo(conta.email());
        assertThat(dados.has("senha")).isFalse();
        assertThat(dados.has("papel")).isFalse();
        assertThat(inexistente.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(inexistente.body()).path("erro").path("codigo").asString())
                .isEqualTo("CONTA_NAO_ENCONTRADA");
    }

    private HttpResponse<String> consultar(String caminho, String token) throws Exception {
        var requisicao = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + caminho))
                .timeout(Duration.ofSeconds(10)).GET();
        if (token != null) requisicao.header("X-Balaio-Service-Token", token);
        return cliente.send(requisicao.build(), HttpResponse.BodyHandlers.ofString());
    }
}
