package br.com.balaio.profile.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
class OrganizadorAdminHttpIntegrationTests {
    private static final Instant AGORA = Instant.parse("2026-10-28T12:00:00Z");
    private static final String SENHA = "Senha descartável de integração";

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

    private UUID conta(String papel, boolean trocaSenhaObrigatoria) {
        var conta = cadastrar.cadastrar(new CadastrarContaPublicaComando(
                "Nome de teste", "conta-" + UUID.randomUUID() + "@example.test", SENHA, "comprador"));
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
