package br.com.balaio.profile.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.CadastrarContaPublicaComando;
import br.com.balaio.profile.account.application.ContaPublicaCadastrada;
import br.com.balaio.profile.account.application.port.in.CadastrarContaPublicaUseCase;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.security.EmissorJwtAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
class OrganizadorAdminHttpIntegrationTests {
    private static final Instant AGORA = Instant.parse("2026-10-28T12:00:00Z");
    private static final String SENHA = "Senha descartável de integração";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int porta;
    @MockitoBean(enforceOverride = true)
    private InicializarAdministradorUseCase inicializarAdministrador;
    @MockitoBean(enforceOverride = true)
    private Clock relogio;
    @Autowired
    private CadastrarContaPublicaUseCase cadastrar;
    @Autowired
    private EmissorJwtAdapter emissor;
    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void preparar() {
        limpar();
        when(relogio.instant()).thenReturn(AGORA);
    }

    @AfterEach
    void limpar() {
        jdbc.update("delete from contas");
        jdbc.update("update controle_administracao set bootstrap_concluido = false where id = 1");
    }

    @Test
    void semTokenRetorna401SemAlterarConta() throws Exception {
        UUID alvo = conta("comprador", false);

        HttpResponse<String> resposta = patch(alvo, "{\"papel\":\"organizador\"}", null);

        assertThat(resposta.statusCode()).isEqualTo(401);
        assertThat(resposta.body()).contains("NAO_AUTENTICADO");
        assertThat(papelNoBanco(alvo)).isEqualTo("comprador");
    }

    @ParameterizedTest
    @ValueSource(strings = { "comprador", "artesao", "organizador" })
    void chamadorNaoAdministradorRecebe403SemAlterarConta(String papelChamador) throws Exception {
        UUID chamador = conta(papelChamador, false);
        UUID alvo = conta("comprador", false);

        HttpResponse<String> resposta = patch(alvo, "{\"papel\":\"organizador\"}", token(chamador));

        assertThat(resposta.statusCode()).isEqualTo(403);
        assertThat(resposta.body()).contains("SEM_PERMISSAO");
        assertThat(papelNoBanco(alvo)).isEqualTo("comprador");
    }

    @Test
    void adminAtribuiOrganizadorAComprador() throws Exception {
        UUID admin = conta("admin", false);
        UUID alvo = conta("comprador", false);

        HttpResponse<String> resposta = patch(alvo, "{\"papel\":\"organizador\"}", token(admin));

        assertThat(resposta.statusCode()).isEqualTo(204);
        assertThat(resposta.body()).isEmpty();
        assertThat(resposta.headers().firstValue("Authorization")).isEmpty();
        assertThat(papelNoBanco(alvo)).isEqualTo("organizador");
        assertThat(papelNoBanco(admin)).isEqualTo("admin");
    }

    @Test
    void adminRemoveOrganizadorDeVoltaParaComprador() throws Exception {
        UUID admin = conta("admin", false);
        UUID alvo = conta("organizador", false);

        HttpResponse<String> resposta = patch(alvo, "{\"papel\":\"comprador\"}", token(admin));

        assertThat(resposta.statusCode()).isEqualTo(204);
        assertThat(papelNoBanco(alvo)).isEqualTo("comprador");
    }

    @Test
    void repeticaoDaMesmaOperacaoEhIdempotente() throws Exception {
        UUID admin = conta("admin", false);
        UUID alvo = conta("comprador", false);

        assertThat(patch(alvo, "{\"papel\":\"organizador\"}", token(admin)).statusCode()).isEqualTo(204);
        assertThat(patch(alvo, "{\"papel\":\"organizador\"}", token(admin)).statusCode()).isEqualTo(204);
        assertThat(papelNoBanco(alvo)).isEqualTo("organizador");
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"papel\":\"admin\"}", "{\"papel\":\"artesao\"}", "{\"papel\":\"Organizador\"}",
            "{\"papel\":\"\"}", "{}", "{\"papel\":" })
    void valoresForaDoContratoRetornam400SemAlterarConta(String corpo) throws Exception {
        UUID admin = conta("admin", false);
        UUID alvo = conta("comprador", false);

        HttpResponse<String> resposta = patch(alvo, corpo, token(admin));

        assertThat(resposta.statusCode()).isEqualTo(400);
        assertThat(resposta.body()).contains("REQUISICAO_INVALIDA");
        assertThat(papelNoBanco(alvo)).isEqualTo("comprador");
    }

    @Test
    void contaInexistenteRetorna404() throws Exception {
        UUID admin = conta("admin", false);

        HttpResponse<String> resposta = patch(UUID.randomUUID(), "{\"papel\":\"organizador\"}", token(admin));

        assertThat(resposta.statusCode()).isEqualTo(404);
        assertThat(resposta.body()).contains("CONTA_NAO_ENCONTRADA");
    }

    @ParameterizedTest
    @ValueSource(strings = { "artesao", "admin" })
    void transicaoQueNaoEhCompradorParaOrganizadorNemOrganizadorParaCompradorEhRecusada(String papelAlvo)
            throws Exception {
        UUID admin = conta("admin", false);
        UUID alvo = conta(papelAlvo, false);

        HttpResponse<String> resposta = patch(alvo, "{\"papel\":\"organizador\"}", token(admin));

        assertThat(resposta.statusCode()).isEqualTo(409);
        assertThat(resposta.body()).contains("TRANSICAO_PAPEL_INVALIDA");
        assertThat(papelNoBanco(alvo)).isEqualTo(papelAlvo);
    }

    @Test
    void artesaoNaoPerdeOPapelAoSerSolicitadoComoComprador() throws Exception {
        UUID admin = conta("admin", false);
        UUID alvo = conta("artesao", false);

        HttpResponse<String> resposta = patch(alvo, "{\"papel\":\"comprador\"}", token(admin));

        assertThat(resposta.statusCode()).isEqualTo(409);
        assertThat(papelNoBanco(alvo)).isEqualTo("artesao");
    }

    @Test
    void administradorComTrocaDeSenhaPendenteNaoProvisiona() throws Exception {
        UUID admin = conta("admin", true);
        UUID alvo = conta("comprador", false);

        HttpResponse<String> resposta = patch(alvo, "{\"papel\":\"organizador\"}", token(admin));

        assertThat(resposta.statusCode()).isEqualTo(403);
        assertThat(papelNoBanco(alvo)).isEqualTo("comprador");
    }

    @Test
    void adminBuscaContaPorEmailERecebeIdNomeEmailEPapel() throws Exception {
        UUID admin = conta("admin", false);
        UUID alvo = conta("organizador", false, "alvo.busca@example.test");

        HttpResponse<String> resposta = get("  Alvo.Busca@Example.TEST ", token(admin));

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(resposta.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(resposta.body())
                .contains("\"id\":\"" + alvo + "\"")
                .contains("\"nome\":\"Nome de teste\"")
                .contains("\"email\":\"alvo.busca@example.test\"")
                .contains("\"papel\":\"organizador\"")
                .contains("\"erro\":null")
                .doesNotContain("senha", "bcrypt", "troca");
    }

    @Test
    void buscaSemTokenRetorna401() throws Exception {
        conta("comprador", false, "sem.token@example.test");

        HttpResponse<String> resposta = get("sem.token@example.test", null);

        assertThat(resposta.statusCode()).isEqualTo(401);
        assertThat(resposta.body()).contains("NAO_AUTENTICADO").doesNotContain("sem.token@example.test");
    }

    @ParameterizedTest
    @ValueSource(strings = { "comprador", "artesao", "organizador" })
    void buscaPorNaoAdministradorRetorna403SemRevelarConta(String papelChamador) throws Exception {
        UUID chamador = conta(papelChamador, false);
        conta("comprador", false, "alvo.oculto@example.test");

        HttpResponse<String> resposta = get("alvo.oculto@example.test", token(chamador));

        assertThat(resposta.statusCode()).isEqualTo(403);
        assertThat(resposta.body()).contains("SEM_PERMISSAO").doesNotContain("alvo.oculto@example.test");
    }

    @Test
    void buscaPorNaoAdministradorComEmailInvalidoContinuaRetornando403() throws Exception {
        UUID chamador = conta("comprador", false);

        assertThat(get("nao-e-email", token(chamador)).statusCode()).isEqualTo(403);
    }

    @Test
    void buscaPorAdministradorComTrocaDeSenhaPendenteRetorna403() throws Exception {
        UUID admin = conta("admin", true);
        conta("comprador", false, "pendente@example.test");

        HttpResponse<String> resposta = get("pendente@example.test", token(admin));

        assertThat(resposta.statusCode()).isEqualTo(403);
        assertThat(resposta.body()).contains("TROCA_SENHA_OBRIGATORIA").doesNotContain("pendente@example.test");
    }

    @Test
    void buscaDeEmailSemContaRetorna404() throws Exception {
        UUID admin = conta("admin", false);

        HttpResponse<String> resposta = get("ninguem@example.test", token(admin));

        assertThat(resposta.statusCode()).isEqualTo(404);
        assertThat(resposta.body()).contains("CONTA_NAO_ENCONTRADA");
    }

    @ParameterizedTest
    @ValueSource(strings = { "nao-e-email", "   ", "a@b" })
    void buscaComEmailInvalidoRetorna400(String email) throws Exception {
        UUID admin = conta("admin", false);

        HttpResponse<String> resposta = get(email, token(admin));

        assertThat(resposta.statusCode()).isEqualTo(400);
        assertThat(resposta.body()).contains("EMAIL_INVALIDO");
    }

    @Test
    void semParametroEmailListaCompradoresEOrganizadoresComOrganizadoresPrimeiro() throws Exception {
        UUID admin = conta("admin", false);
        UUID bia = conta("comprador", false, "bia@example.test", "bia Compradora");
        UUID zeca = conta("organizador", false, "zeca@example.test", "Zeca Organizador");
        UUID ana = conta("comprador", false, "ana@example.test", "Ana Compradora");
        conta("artesao", false, "artesa@example.test", "Artesã Fora");
        jdbc.update("update contas set imagem = ? where id = ?", "/api/v1/arquivos/perfis/" + zeca, zeca);

        HttpResponse<String> resposta = listar("", token(admin));

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(resposta.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode corpo = JSON.readTree(resposta.body());
        assertThat(corpo.get("erro").isNull()).isTrue();
        assertThat(ids(corpo)).containsExactly(zeca.toString(), ana.toString(), bia.toString());
        assertThat(paginacao(corpo)).containsExactly(1L, 10L, 3L, 1L);
        JsonNode primeiro = corpo.get("dados").get(0);
        assertThat(primeiro.get("nome").asText()).isEqualTo("Zeca Organizador");
        assertThat(primeiro.get("email").asText()).isEqualTo("zeca@example.test");
        assertThat(primeiro.get("papel").asText()).isEqualTo("organizador");
        assertThat(primeiro.get("imagem").asText()).isEqualTo("/api/v1/arquivos/perfis/" + zeca);
        assertThat(corpo.get("dados").get(1).get("imagem").isNull()).isTrue();
        assertThat(resposta.body()).doesNotContain("senha", "bcrypt", "troca", "artesa@example.test", admin.toString());
    }

    @Test
    void listagemBuscaPorNomeOuEmailSemDiferenciarCaixaETrataCuringasComoTexto() throws Exception {
        UUID admin = conta("admin", false);
        UUID porNome = conta("comprador", false, "primeira@example.test", "Maria Rendeira");
        UUID porEmail = conta("organizador", false, "renda_festa@example.test", "Festa Junina");
        conta("comprador", false, "outra@example.test", "Outra Pessoa");
        UUID percentual = conta("comprador", false, "cem@example.test", "Cem 100% Barro");

        assertThat(ids(JSON.readTree(listar("?busca=%20REND%20", token(admin)).body())))
                .containsExactly(porEmail.toString(), porNome.toString());
        assertThat(ids(JSON.readTree(listar("?busca=primeira@example.test", token(admin)).body())))
                .containsExactly(porNome.toString());
        assertThat(ids(JSON.readTree(listar("?busca=%25", token(admin)).body()))).containsExactly(percentual.toString());
        assertThat(ids(JSON.readTree(listar("?busca=a_f", token(admin)).body()))).containsExactly(porEmail.toString());
        JsonNode vazia = JSON.readTree(listar("?busca=ninguem", token(admin)).body());
        assertThat(ids(vazia)).isEmpty();
        assertThat(paginacao(vazia)).containsExactly(1L, 10L, 0L, 1L);
        assertThat(listar("?busca=" + "x".repeat(101), token(admin)).statusCode()).isEqualTo(400);
    }

    @Test
    void listagemFiltraPorPapelEPagina() throws Exception {
        UUID admin = conta("admin", false);
        UUID organizador = conta("organizador", false, "org@example.test", "Organizador Único");
        for (int i = 0; i < 12; i++) {
            conta("comprador", false, "comprador" + i + "@example.test", String.format("Comprador %02d", i));
        }

        assertThat(ids(JSON.readTree(listar("?papel=organizador", token(admin)).body())))
                .containsExactly(organizador.toString());
        JsonNode compradores = JSON.readTree(listar("?papel=comprador", token(admin)).body());
        assertThat(paginacao(compradores)).containsExactly(1L, 10L, 12L, 2L);
        assertThat(compradores.get("dados").get(0).get("nome").asText()).isEqualTo("Comprador 00");
        JsonNode todos = JSON.readTree(listar("?papel=todos&pagina=2", token(admin)).body());
        assertThat(paginacao(todos)).containsExactly(2L, 10L, 13L, 2L);
        assertThat(todos.get("dados").size()).isEqualTo(3);
        assertThat(todos.get("dados").get(2).get("nome").asText()).isEqualTo("Comprador 11");
        assertThat(paginacao(JSON.readTree(listar("?pagina=99&tamanho=5", token(admin)).body())))
                .containsExactly(3L, 5L, 13L, 3L);
        assertThat(paginacao(JSON.readTree(listar("?tamanho=500", token(admin)).body())))
                .containsExactly(1L, 50L, 13L, 1L);
        for (String invalido : new String[] { "?papel=artesao", "?papel=admin", "?papel=Organizador", "?pagina=abc",
                "?tamanho=dez" }) {
            HttpResponse<String> resposta = listar(invalido, token(admin));
            assertThat(resposta.statusCode()).as(invalido).isEqualTo(400);
            assertThat(resposta.body()).contains("REQUISICAO_INVALIDA");
        }
    }

    @Test
    void listagemExigeAdministradorSemRevelarContas() throws Exception {
        conta("comprador", false, "listada@example.test", "Pessoa Listada");

        HttpResponse<String> semToken = listar("", null);
        assertThat(semToken.statusCode()).isEqualTo(401);
        assertThat(semToken.body()).contains("NAO_AUTENTICADO").doesNotContain("listada@example.test");
        for (String papel : new String[] { "comprador", "artesao", "organizador" }) {
            HttpResponse<String> resposta = listar("?papel=todos", token(conta(papel, false)));
            assertThat(resposta.statusCode()).as(papel).isEqualTo(403);
            assertThat(resposta.body()).contains("SEM_PERMISSAO").doesNotContain("listada@example.test");
        }
        HttpResponse<String> trocaPendente = listar("", token(conta("admin", true)));
        assertThat(trocaPendente.statusCode()).isEqualTo(403);
        assertThat(trocaPendente.body()).contains("TROCA_SENHA_OBRIGATORIA").doesNotContain("listada@example.test");
    }

    private UUID conta(String papel, boolean trocaSenhaObrigatoria) {
        return conta(papel, trocaSenhaObrigatoria, "conta-" + UUID.randomUUID() + "@example.test");
    }

    private UUID conta(String papel, boolean trocaSenhaObrigatoria, String email) {
        return conta(papel, trocaSenhaObrigatoria, email, "Nome de teste");
    }

    private UUID conta(String papel, boolean trocaSenhaObrigatoria, String email, String nome) {
        var conta = cadastrar.cadastrar(new CadastrarContaPublicaComando(
                nome, email, SENHA, "comprador"));
        jdbc.update("update contas set papel = ?, troca_senha_obrigatoria = ? where id = ?",
                papel, trocaSenhaObrigatoria, conta.id());
        return conta.id();
    }

    private String token(UUID id) {
        Papel papel = Papel.fromCodigo(papelNoBanco(id));
        return emissor.emitir(new ContaPublicaCadastrada(id, "Nome de teste", "token@example.test", papel));
    }

    private String papelNoBanco(UUID id) {
        return jdbc.queryForObject("select papel from contas where id = ?", String.class, id);
    }

    private HttpResponse<String> get(String email, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/v1/admin/contas?email="
                        + URLEncoder.encode(email, StandardCharsets.UTF_8)))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return cliente.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> listar(String consulta, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/v1/admin/contas" + consulta))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return cliente.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> ids(JsonNode corpo) {
        var ids = new ArrayList<String>();
        corpo.get("dados").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private static List<Long> paginacao(JsonNode corpo) {
        JsonNode p = corpo.get("paginacao");
        return List.of(p.get("pagina").asLong(), p.get("tamanho").asLong(), p.get("total").asLong(),
                p.get("totalPaginas").asLong());
    }

    private HttpResponse<String> patch(UUID alvo, String corpo, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/v1/admin/contas/" + alvo + "/papel"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(corpo));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return cliente.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
