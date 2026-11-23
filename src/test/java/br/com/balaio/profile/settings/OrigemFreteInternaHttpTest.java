package br.com.balaio.profile.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "balaio.internal-token=token-interno-de-teste-1234567890ab")
@Import(PostgreSQLTestConfiguration.class)
class OrigemFreteInternaHttpTest {
    private static final String TOKEN = "token-interno-de-teste-1234567890ab";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HASH = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("SenhaDeTeste123!");

    @LocalServerPort
    private int porta;
    @MockitoBean(enforceOverride = true)
    private InicializarAdministradorUseCase inicializarAdministrador;
    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private UUID artesao;
    private UUID perfil;
    private UUID comprador;

    @BeforeEach
    void preparar() {
        artesao = conta("artesao");
        comprador = conta("comprador");
        perfil = UUID.randomUUID();
        jdbc.update("INSERT INTO perfis_artesao(id,conta_id,slug,nome,atelie) VALUES (?,?,?,?,?)",
                perfil, artesao, "artesao-" + artesao, "Nome artístico", "Ateliê de teste");
    }

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM contas WHERE id IN (?,?)", artesao, comprador);
    }

    @Test
    void exigeTokenInternoValido() throws Exception {
        String caminho = caminho(artesao);
        var semToken = consultar(caminho, null);
        var tokenErrado = consultar(caminho, "token-incorreto-de-teste-1234567890ab");

        assertThat(semToken.statusCode()).isEqualTo(403);
        assertThat(tokenErrado.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(tokenErrado.body()).path("erro").path("codigo").asString()).isEqualTo("SEM_PERMISSAO");
        assertThat(tokenErrado.body()).doesNotContain("cepOrigem");
    }

    @Test
    void devolveCepConfiguradoSomenteComDigitosEPrazoEPausa() throws Exception {
        jdbc.update("""
                INSERT INTO configuracoes_atelie (perfil_id, cep_origem, prazo_padrao_dias, aceita_encomendas,
                encomendas_pausadas, chave_pix) VALUES (?, '56800-000', 12, true, true, 'pix-secreto')
                """, perfil);

        var resposta = consultar(caminho(artesao), TOKEN);

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(resposta.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode dados = JSON.readTree(resposta.body()).path("dados");
        assertThat(dados.path("cepOrigem").asString()).isEqualTo("56800000");
        assertThat(dados.path("prazoPadraoDias").asInt()).isEqualTo(12);
        assertThat(dados.path("encomendasPausadas").asBoolean()).isTrue();
        assertThat(resposta.body()).doesNotContain("pix-secreto", "chavePix", "aceitaEncomendas");
    }

    @Test
    void ateliesSemConfiguracaoOuSemCepDevolvemCepNulo() throws Exception {
        var semConfiguracao = JSON.readTree(consultar(caminho(artesao), TOKEN).body()).path("dados");
        assertThat(semConfiguracao.path("cepOrigem").isNull()).isTrue();
        assertThat(semConfiguracao.path("prazoPadraoDias").asInt()).isEqualTo(1);
        assertThat(semConfiguracao.path("encomendasPausadas").asBoolean()).isFalse();

        jdbc.update("INSERT INTO configuracoes_atelie (perfil_id, prazo_padrao_dias) VALUES (?, 5)", perfil);
        var semCep = JSON.readTree(consultar(caminho(artesao), TOKEN).body()).path("dados");
        assertThat(semCep.path("cepOrigem").isNull()).isTrue();
        assertThat(semCep.path("prazoPadraoDias").asInt()).isEqualTo(5);
    }

    @Test
    void contaSemAtelieOuInexistenteDevolve404() throws Exception {
        var semAtelie = consultar(caminho(comprador), TOKEN);
        var inexistente = consultar(caminho(UUID.randomUUID()), TOKEN);

        assertThat(semAtelie.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(semAtelie.body()).path("erro").path("codigo").asString()).isEqualTo("PERFIL_NAO_ENCONTRADO");
        assertThat(inexistente.statusCode()).isEqualTo(404);
    }

    private UUID conta(String papel) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO contas(id,nome,email,senha_hash,papel) VALUES (?,?,?,?,?)",
                id, "Conta teste", "origem-frete-" + id + "@example.test", HASH, papel);
        return id;
    }

    private String caminho(UUID conta) {
        return "/internal/v1/contas/" + conta + "/origem-frete";
    }

    private HttpResponse<String> consultar(String caminho, String token) throws Exception {
        var requisicao = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + caminho))
                .timeout(Duration.ofSeconds(10)).GET();
        if (token != null) requisicao.header("X-Balaio-Service-Token", token);
        return cliente.send(requisicao.build(), HttpResponse.BodyHandlers.ofString());
    }
}
