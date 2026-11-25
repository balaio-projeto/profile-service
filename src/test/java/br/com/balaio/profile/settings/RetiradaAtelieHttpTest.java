package br.com.balaio.profile.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.SessaoJwtTestFixtures;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "balaio.internal-token=token-interno-de-teste-1234567890ab")
@Import(PostgreSQLTestConfiguration.class)
class RetiradaAtelieHttpTest {
    private static final String TOKEN_INTERNO = "token-interno-de-teste-1234567890ab";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HASH = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("SenhaDeTeste123!");

    @LocalServerPort int porta;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(enforceOverride = true) InicializarAdministradorUseCase administrador;
    private UUID artesao;
    private String slug;

    @BeforeEach
    void preparar() {
        artesao = UUID.randomUUID();
        slug = "retirada-" + artesao;
        jdbc.update("INSERT INTO contas(id,nome,email,senha_hash,papel) VALUES (?,?,?,?,?)",
                artesao, "Artesã", "retirada-" + artesao + "@example.test", HASH, "artesao");
        jdbc.update("INSERT INTO perfis_artesao(id,conta_id,slug,nome,atelie) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), artesao, slug, "Nome artístico", "Ateliê de teste");
    }

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM contas WHERE id = ?", artesao);
    }

    @Test
    void retiradaExigeBairroECidadeEEntregaLocalExigeCep() throws Exception {
        assertErro(patch("{\"aceitaRetirada\":true}"), "retiradaBairro");
        assertErro(patch("{\"aceitaRetirada\":true,\"retiradaBairro\":\"Boa Viagem\"}"), "retiradaCidade");
        assertErro(patch("{\"aceitaEntregaLocal\":true}"), "cepOrigem");
        assertErro(patch("{\"retiradaBairro\":\"" + "a".repeat(81) + "\"}"), "retiradaBairro");
        assertErro(patch("{\"aceitaRetirada\":\"sim\"}"), "aceitaRetirada");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM configuracoes_atelie c JOIN perfis_artesao p "
                + "ON p.id = c.perfil_id WHERE p.conta_id = ?", Integer.class, artesao)).isZero();

        var salvo = patch("{\"cepOrigem\":\"51020-000\",\"aceitaRetirada\":true,\"aceitaEntregaLocal\":true,"
                + "\"retiradaBairro\":\" Boa Viagem \",\"retiradaCidade\":\"Recife\"}");
        assertThat(salvo.statusCode()).as(salvo.body()).isEqualTo(200);
        JsonNode dados = JSON.readTree(salvo.body()).path("dados");
        assertThat(dados.path("aceitaRetirada").asBoolean()).isTrue();
        assertThat(dados.path("aceitaEntregaLocal").asBoolean()).isTrue();
        assertThat(dados.path("retiradaBairro").asString()).isEqualTo("Boa Viagem");

        var parcial = patch("{\"prazoPadraoDias\":9}");
        assertThat(JSON.readTree(parcial.body()).path("dados").path("retiradaCidade").asString()).isEqualTo("Recife");
    }

    @Test
    void origemInternaInformaRetiradaSemExporEnderecoCompleto() throws Exception {
        patch("{\"cepOrigem\":\"51020-000\",\"aceitaRetirada\":true,\"retiradaBairro\":\"Boa Viagem\",\"retiradaCidade\":\"Recife\"}");

        var resposta = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta
                + "/internal/v1/contas/" + artesao + "/origem-frete")).header("X-Balaio-Service-Token", TOKEN_INTERNO)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonNode dados = JSON.readTree(resposta.body()).path("dados");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(dados.path("cepOrigem").asString()).isEqualTo("51020000");
        assertThat(dados.path("aceitaRetirada").asBoolean()).isTrue();
        assertThat(dados.path("aceitaEntregaLocal").asBoolean()).isFalse();
        assertThat(dados.path("retiradaBairro").asString()).isEqualTo("Boa Viagem");
        assertThat(dados.path("retiradaCidade").asString()).isEqualTo("Recife");
        assertThat(resposta.body()).doesNotContain("chavePix");
        assertThat(api("GET", "/artesaos/" + slug, null, false).body()).doesNotContain("retiradaBairro", "aceitaRetirada");
    }

    private void assertErro(HttpResponse<String> resposta, String campo) throws Exception {
        assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(400);
        assertThat(resposta.body()).contains(campo);
    }

    private HttpResponse<String> patch(String corpo) throws Exception {
        return api("PATCH", "/artesaos/" + slug + "/configuracoes", corpo, true);
    }

    private HttpResponse<String> api(String metodo, String caminho, String corpo, boolean autenticado) throws Exception {
        var requisicao = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/v1" + caminho))
                .header("Content-Type", "application/json");
        if (autenticado) {
            var claims = SessaoJwtTestFixtures.claims(artesao, Instant.now());
            claims.put("papel", "artesao");
            requisicao.header("Authorization", "Bearer " + SessaoJwtTestFixtures.token(claims));
        }
        return HTTP.send(requisicao.method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(corpo)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
