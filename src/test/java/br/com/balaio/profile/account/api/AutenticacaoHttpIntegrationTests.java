package br.com.balaio.profile.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;

import br.com.balaio.profile.AutenticacaoTestFixtures;
import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.CadastrarContaPublicaComando;
import br.com.balaio.profile.account.application.port.in.CadastrarContaPublicaUseCase;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.account.security.EmissorJwtAdapter;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
class AutenticacaoHttpIntegrationTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String EMAIL = "login.http@example.test";
    private static final String SENHA = "  Senha ${LITERAL} de fixture  ";

    @LocalServerPort
    private int porta;
    @MockitoBean(enforceOverride = true)
    private InicializarAdministradorUseCase inicializarAdministrador;
    @MockitoSpyBean
    private EmissorJwtAdapter emissor;
    @Autowired
    private CadastrarContaPublicaUseCase cadastrar;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    @AfterEach
    void limparDadosDescartaveis() {
        jdbc.update("delete from contas");
        jdbc.update("update controle_administracao set bootstrap_concluido = false where id = 1");
    }

    @ParameterizedTest
    @MethodSource("loginsValidos")
    void loginEAliasUsamIdentidadeEPapelAtuaisDoBanco(String rota, String papel) throws Exception {
        UUID id = criarConta("comprador");
        jdbc.update("update contas set papel = ?, troca_senha_obrigatoria = ? where id = ?", papel, papel.equals("admin"), id);

        Resposta resposta = enviar(rota, credenciais("  LOGIN.HTTP@EXAMPLE.TEST  ", SENHA), Map.of());

        assertThat(resposta.status()).isEqualTo(200);
        Map<String, Object> usuario = dados(resposta);
        assertThat(resposta.corpo()).containsOnlyKeys("dados", "erro").containsEntry("erro", null);
        assertThat(usuario).containsOnlyKeys(papel.equals("admin")
                ? new String[] { "id", "nome", "email", "papel", "imagem" }
                : new String[] { "id", "nome", "email", "papel", "perfil", "imagem" })
                .containsEntry("id", id.toString()).containsEntry("nome", "Maria Silva")
                .containsEntry("email", EMAIL).containsEntry("papel", papel)
                .containsEntry("imagem", "");
        if (!papel.equals("admin")) {
            assertThat(usuario).containsEntry("perfil", papel);
        }
        assertThat(resposta.cookies()).hasSize(1);
        String token = AutenticacaoTestFixtures.assertCookie(resposta.cookies().getFirst(), true, 3600);
        AutenticacaoTestFixtures.assertJwt(token, id, papel, resposta.antes(), resposta.depois());
        assertCorpoSemSegredos(resposta);
        assertThat(jdbc.queryForObject("select count(*) from contas", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select troca_senha_obrigatoria from contas where id = ?", Boolean.class, id))
                .isEqualTo(papel.equals("admin"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/auth/login", "/auth/entrar" })
    void camposDeIdentidadeForjadosNoLoginNaoSubstituemDadosDoBanco(String rota) throws Exception {
        UUID id = criarConta("comprador");
        String corpo = JSON.writeValueAsString(Map.of("email", EMAIL, "senha", SENHA,
                "papel", "admin", "perfil", "admin", "id", UUID.randomUUID().toString()));
        Resposta resposta = enviar(rota, corpo, Map.of());

        assertThat(resposta.status()).isEqualTo(200);
        assertThat(dados(resposta)).containsEntry("id", id.toString()).containsEntry("papel", "comprador");
        String token = AutenticacaoTestFixtures.assertCookie(resposta.cookies().getFirst(), true, 3600);
        AutenticacaoTestFixtures.assertJwt(token, id, "comprador", resposta.antes(), resposta.depois());
    }

    @Test
    void loginRealVerificaHashLegadoSemAplicarMinimoDoCadastro() throws Exception {
        UUID id = criarConta("comprador");
        jdbc.update("update contas set senha_hash = ? where id = ?", passwordEncoder.encode("curta"), id);
        Resposta resposta = enviar("/auth/login", credenciais(EMAIL, "curta"), Map.of());
        assertThat(resposta.status()).isEqualTo(200);
        String token = AutenticacaoTestFixtures.assertCookie(resposta.cookies().getFirst(), true, 3600);
        AutenticacaoTestFixtures.assertJwt(token, id, "comprador", resposta.antes(), resposta.depois());
    }

    @ParameterizedTest
    @ValueSource(strings = { "/auth/login", "/auth/entrar" })
    void contaAusenteESenhaErradaTemMesmaResposta401SemCookie(String rota) throws Exception {
        criarConta("comprador");

        Resposta ausente = enviar(rota, credenciais("ausente@example.test", SENHA), Map.of());
        Resposta errada = enviar(rota, credenciais(EMAIL, "Outra senha de fixture"), Map.of());
        Resposta semEspacos = enviar(rota, credenciais(EMAIL, SENHA.strip()), Map.of());
        Resposta somenteEspacos = enviar(rota, credenciais(EMAIL, "  "), Map.of());

        assertErro(ausente, 401, "CREDENCIAIS_INVALIDAS");
        assertErro(errada, 401, "CREDENCIAIS_INVALIDAS");
        assertErro(semEspacos, 401, "CREDENCIAIS_INVALIDAS");
        assertErro(somenteEspacos, 401, "CREDENCIAIS_INVALIDAS");
        assertThat(ausente.corpo()).isEqualTo(errada.corpo()).isEqualTo(semEspacos.corpo());
        verifyNoInteractions(emissor);
    }

    @ParameterizedTest
    @MethodSource("loginsInvalidos")
    void loginInvalidoMantemEnvelopeSemCookie(String rota, String corpo) throws Exception {
        assertErro(enviar(rota, corpo, Map.of()), 400, "REQUISICAO_INVALIDA");
        assertThat(jdbc.queryForObject("select count(*) from contas", Long.class)).isZero();
        verifyNoInteractions(emissor);
    }

    @Test
    void loginRepetidoEmiteJtiDiferenteSemCriarOutraConta() throws Exception {
        criarConta("artesao");
        Resposta primeira = enviar("/auth/login", credenciais(EMAIL, SENHA), Map.of());
        Resposta segunda = enviar("/auth/login", credenciais(EMAIL, SENHA), Map.of());

        assertThat(primeira.status()).isEqualTo(200);
        assertThat(segunda.status()).isEqualTo(200);
        String token1 = AutenticacaoTestFixtures.assertCookie(primeira.cookies().getFirst(), true, 3600);
        String token2 = AutenticacaoTestFixtures.assertCookie(segunda.cookies().getFirst(), true, 3600);
        assertThat(SignedJWT.parse(token1).getJWTClaimsSet().getJWTID())
                .isNotEqualTo(SignedJWT.parse(token2).getJWTClaimsSet().getJWTID());
        assertThat(jdbc.queryForObject("select count(*) from contas", Long.class)).isEqualTo(1);
    }

    @Test
    void logoutLimpaMesmoCookieSemRevogarTokenCopiadoEPermiteRepeticao() throws Exception {
        criarConta("comprador");
        Resposta login = enviar("/auth/login", credenciais(EMAIL, SENHA), Map.of());
        assertThat(login.status()).isEqualTo(200);
        String token = AutenticacaoTestFixtures.assertCookie(login.cookies().getFirst(), true, 3600);
        SignedJWT copiado = SignedJWT.parse(token);

        Resposta logout = enviar("/auth/logout", "{}", Map.of("Cookie", "balaio_sessao=" + token,
                "Origin", AutenticacaoTestFixtures.ORIGIN));

        assertThat(logout.status()).isEqualTo(200);
        assertThat(logout.corpo()).containsOnlyKeys("dados", "erro").containsEntry("erro", null);
        assertThat(dados(logout)).containsOnlyKeys("encerrada").containsEntry("encerrada", true);
        assertThat(logout.cookies()).hasSize(1);
        AutenticacaoTestFixtures.assertCookie(logout.cookies().getFirst(), true, 0);
        assertThat(copiado.verify(new RSASSAVerifier(AutenticacaoTestFixtures.publicKey()))).isTrue();
        assertThat(copiado.getJWTClaimsSet().getExpirationTime().toInstant()).isAfter(Instant.now());

        Resposta repetido = enviar("/auth/logout", "{}", Map.of());
        assertThat(repetido.status()).isEqualTo(200);
        assertThat(repetido.cookies()).hasSize(1);
        AutenticacaoTestFixtures.assertCookie(repetido.cookies().getFirst(), true, 0);
        assertThat(jdbc.queryForObject("select count(*) from contas", Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("origensNegadas")
    void origemNaoPermitidaBloqueiaAsQuatroRotasAntesDeEfeitos(String rota, String origin) throws Exception {
        Resposta resposta = enviar(rota, corpoPara(rota), Map.of("Origin", origin));
        assertErro(resposta, 403, "SEM_PERMISSAO");
        assertThat(jdbc.queryForObject("select count(*) from contas", Long.class)).isZero();
        verifyNoInteractions(emissor);
    }

    @ParameterizedTest
    @ValueSource(strings = { "/auth/cadastro", "/auth/login", "/auth/entrar", "/auth/logout" })
    void cookieSemOriginEhRejeitadoMesmoComBearer(String rota) throws Exception {
        Resposta resposta = enviar(rota, corpoPara(rota), Map.of("Cookie", "balaio_sessao=fixture-invalida",
                "Authorization", "Bearer fixture-sem-autoridade"));
        assertErro(resposta, 403, "SEM_PERMISSAO");
        assertThat(jdbc.queryForObject("select count(*) from contas", Long.class)).isZero();
        verifyNoInteractions(emissor);
    }

    @ParameterizedTest
    @ValueSource(strings = { "/auth/cadastro", "/auth/login", "/auth/entrar", "/auth/logout" })
    void origemPermitidaPassaPelaProtecao(String rota) throws Exception {
        if (rota.equals("/auth/login") || rota.equals("/auth/entrar")) {
            criarConta("comprador");
        }
        Resposta resposta = enviar(rota, corpoPara(rota), Map.of("Origin", AutenticacaoTestFixtures.ORIGIN));
        assertThat(resposta.status()).isEqualTo(rota.equals("/auth/cadastro") ? 201 : 200);
        assertThat(resposta.cookies()).hasSize(1);
    }

    @Test
    void falhaDeAssinaturaAposCommitRetorna500SemCookieMasContaPermanece() throws Exception {
        doThrow(new IllegalStateException("fixture-privada-na-causa")).when(emissor).emitir(any());
        try {
            Resposta resposta = enviar("/auth/cadastro", corpoPara("/auth/cadastro"), Map.of());
            assertErro(resposta, 500, "AUTENTICACAO_INDISPONIVEL");
            assertThat(resposta.texto()).doesNotContain("fixture-privada-na-causa");
            assertThat(jdbc.queryForObject("select count(*) from contas where email = ?", Long.class, EMAIL)).isEqualTo(1);
        } finally {
            reset(emissor);
        }
    }

    private UUID criarConta(String papel) {
        return cadastrar.cadastrar(new CadastrarContaPublicaComando("Maria Silva", EMAIL, SENHA, papel)).id();
    }

    private Resposta enviar(String rota, String corpo, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + "/api/v1" + rota))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo));
        headers.forEach(builder::header);
        Instant antes = Instant.now();
        HttpResponse<String> resposta = cliente.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        Instant depois = Instant.now();
        if (resposta.statusCode() == 200 || resposta.statusCode() == 201) {
            assertThat(resposta.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> json = JSON.readValue(resposta.body(), Map.class);
        return new Resposta(resposta.statusCode(), json, resposta.body(), resposta.headers().allValues("Set-Cookie"), antes, depois);
    }

    private void assertErro(Resposta resposta, int status, String codigo) {
        assertThat(resposta.status()).isEqualTo(status);
        assertThat(resposta.corpo()).containsOnlyKeys("dados", "erro").containsEntry("dados", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> erro = (Map<String, Object>) resposta.corpo().get("erro");
        assertThat(erro).containsOnlyKeys("codigo", "mensagem").containsEntry("codigo", codigo);
        assertThat(resposta.cookies()).isEmpty();
        assertCorpoSemSegredos(resposta);
    }

    private void assertCorpoSemSegredos(Resposta resposta) {
        assertThat(resposta.texto()).doesNotContain(SENHA, "{bcrypt}", "senhaHash", "senha_hash", "senhaSal",
                "senhaProtegida", "\"senha\"", "\"token\"", "\"jwt\"", "PRIVATE KEY", "stackTrace", "java.lang.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dados(Resposta resposta) {
        return (Map<String, Object>) resposta.corpo().get("dados");
    }

    private static String credenciais(String email, String senha) {
        return JSON.writeValueAsString(Map.of("email", email, "senha", senha));
    }

    private static String corpoPara(String rota) {
        return rota.equals("/auth/cadastro")
                ? JSON.writeValueAsString(Map.of("nome", "Maria Silva", "email", EMAIL, "senha", SENHA, "perfil", "comprador"))
                : rota.equals("/auth/logout") ? "{}" : credenciais(EMAIL, SENHA);
    }

    private static Stream<Arguments> loginsValidos() {
        return Stream.of("/auth/login", "/auth/entrar").flatMap(rota -> Stream.of("comprador", "artesao", "admin")
                .map(papel -> Arguments.of(rota, papel)));
    }

    private static Stream<Arguments> loginsInvalidos() {
        return Stream.of("/auth/login", "/auth/entrar").flatMap(rota -> Stream.of("", "null", "[]", "{", "{}",
                "{\"email\":null,\"senha\":\"fixture\"}", "{\"email\":42,\"senha\":\"fixture\"}",
                "{\"email\":\"invalido\",\"senha\":\"fixture\"}",
                "{\"email\":\"login.http@example.test\",\"senha\":null}",
                "{\"email\":\"login.http@example.test\",\"senha\":42}",
                "{\"email\":\"login.http@example.test\",\"senha\":\"\"}",
                "{\"email\":null,\"email\":\"login.http@example.test\",\"senha\":\"fixture\"}",
                "{\"email\":\"login.http@example.test\",\"senha\":null,\"senha\":\"fixture\"}",
                "{\"email\":\"login.http@example.test\",\"em\\u0061il\":\"outro@example.test\",\"senha\":\"fixture\"}")
                .map(corpo -> Arguments.of(rota, corpo)));
    }

    private static Stream<Arguments> origensNegadas() {
        return Stream.of("/auth/cadastro", "/auth/login", "/auth/entrar", "/auth/logout").flatMap(rota -> Stream.of(
                "https://evil.example.test", "https://frontend.example.test.evil.test", "http://frontend.example.test",
                "https://frontend.example.test:444", "null") .map(origin -> Arguments.of(rota, origin)));
    }

    private record Resposta(int status, Map<String, Object> corpo, String texto, List<String> cookies, Instant antes, Instant depois) {
    }
}
