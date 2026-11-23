package br.com.balaio.profile.artisan.api;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.SessaoJwtTestFixtures;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC35 (TS21): edição parcial do perfil do artesão pelo painel, com persistência,
 * reflexo na página pública e rejeição de campos inválidos sem alterar o perfil.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
class PerfilEdicaoHttpTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(enforceOverride = true) InicializarAdministradorUseCase bootstrap;

    UUID conta;
    UUID perfil;
    String slug;

    @BeforeEach void preparar() {
        conta = UUID.randomUUID();
        perfil = UUID.randomUUID();
        slug = "edicao-" + perfil;
        jdbc.update("INSERT INTO contas(id,nome,email,senha_hash,papel) VALUES (?,?,?,?,?)",
                conta, "Artesão Edição", conta + "@test.invalid", "{bcrypt}$2b$12$" + ".".repeat(53), "artesao");
        jdbc.update("INSERT INTO perfis_artesao(id,conta_id,slug,nome,atelie,historia,territorio,tecnica,imagem) VALUES (?,?,?,?,?,?,?,?,?)",
                perfil, conta, slug, "Nome original", "Ateliê original", "História original",
                "Agreste", "Cerâmica", "/foto-original.webp");
    }

    @AfterEach void limpar() {
        jdbc.update("DELETE FROM perfis_artesao WHERE id=?", perfil);
        jdbc.update("DELETE FROM contas WHERE id=?", conta);
    }

    @Test
    void edicaoParcialPersisteECamposNaoEnviadosPermanecem() throws Exception {
        var response = patch("{\"nome\":\"Nome novo\",\"historia\":\"História nova\"}", conta);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var dados = JSON.readTree(response.body()).get("dados");
        assertThat(dados.get("nome").asText()).isEqualTo("Nome novo");
        assertThat(dados.get("historia").asText()).isEqualTo("História nova");

        var linha = jdbc.queryForMap("SELECT nome, atelie, historia, territorio, tecnica, imagem FROM perfis_artesao WHERE id=?", perfil);
        assertThat(linha.get("nome")).isEqualTo("Nome novo");
        assertThat(linha.get("historia")).isEqualTo("História nova");
        assertThat(linha.get("atelie")).isEqualTo("Ateliê original");
        assertThat(linha.get("territorio")).isEqualTo("Agreste");
        assertThat(linha.get("tecnica")).isEqualTo("Cerâmica");
        assertThat(linha.get("imagem")).isEqualTo("/foto-original.webp");
    }

    @Test
    void edicaoAparecePeloPainelEPelaPaginaPublica() throws Exception {
        patch("{\"atelie\":\"Ateliê renovado\",\"tecnica\":\"Renda de bilro\"}", conta);

        var meu = JSON.readTree(get("/api/v1/artesao/me", conta).body()).get("dados");
        assertThat(meu.get("atelie").asText()).isEqualTo("Ateliê renovado");
        assertThat(meu.get("tecnica").asText()).isEqualTo("Renda de bilro");

        var publico = JSON.readTree(get("/api/v1/artesaos/" + slug, null).body()).get("dados");
        assertThat(publico.get("atelie").asText()).isEqualTo("Ateliê renovado");
        assertThat(publico.get("tecnica").asText()).isEqualTo("Renda de bilro");
    }

    @Test
    void campoInvalidoRecusaEMantemPerfilAtual() throws Exception {
        var naoPermitido = patch("{\"nome\":\"Tentativa\",\"papel\":\"admin\"}", conta);
        assertThat(naoPermitido.statusCode()).isEqualTo(400);
        assertErroPorCampo(naoPermitido, "papel");

        var tipoInvalido = patch("{\"nome\":123}", conta);
        assertThat(tipoInvalido.statusCode()).isEqualTo(400);
        assertErroPorCampo(tipoInvalido, "nome");

        var vazio = patch("{}", conta);
        assertThat(vazio.statusCode()).isEqualTo(400);

        assertThat(jdbc.queryForObject("SELECT nome FROM perfis_artesao WHERE id=?", String.class, perfil))
                .isEqualTo("Nome original");
        assertThat(jdbc.queryForObject("SELECT historia FROM perfis_artesao WHERE id=?", String.class, perfil))
                .isEqualTo("História original");
    }

    @Test
    void edicaoSemSessaoEhRecusada() throws Exception {
        var response = patch("{\"nome\":\"Sem sessão\"}", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT nome FROM perfis_artesao WHERE id=?", String.class, perfil))
                .isEqualTo("Nome original");
    }

    private void assertErroPorCampo(HttpResponse<String> response, String campo) throws Exception {
        JsonNode corpo = JSON.readTree(response.body());
        assertThat(corpo.get("dados").isNull()).isTrue();
        assertThat(corpo.get("erro").toString()).contains(campo);
        assertThat(response.body()).doesNotContain("stackTrace", "org.postgresql", "senha_hash");
    }

    private HttpResponse<String> patch(String corpo, UUID ator) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/artesao/me"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(corpo));
        if (ator != null) request.header("Authorization", "Bearer " + token(ator));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String caminho, UUID ator) throws Exception {
        var request = HttpRequest.newBuilder(uri(caminho)).GET();
        if (ator != null) request.header("Authorization", "Bearer " + token(ator));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(UUID id) {
        var claims = SessaoJwtTestFixtures.claims(id, Instant.now());
        claims.put("papel", "artesao");
        return SessaoJwtTestFixtures.token(claims);
    }

    private URI uri(String caminho) {
        return URI.create("http://localhost:" + port + caminho);
    }
}
