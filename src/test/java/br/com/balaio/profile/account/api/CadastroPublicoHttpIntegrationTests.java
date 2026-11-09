package br.com.balaio.profile.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.AutenticacaoTestFixtures;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.account.security.CodificarSenhaAdapter;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
class CadastroPublicoHttpIntegrationTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String EMAIL = "maria.http@example.test";
    private static final String SENHA = "  Senha ${LITERAL} de fixture  ";

    @LocalServerPort
    private int porta;

    @MockitoBean(enforceOverride = true)
    private InicializarAdministradorUseCase inicializarAdministrador;

    @MockitoSpyBean
    private CodificarSenhaAdapter codificador;

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

    @ParameterizedTest(name = "cadastro publico HTTP [{index}]")
    @MethodSource("papeisPublicos")
    void cadastraEDevolveSomenteUsuarioPublico(String camposPapel, String papelEsperado) throws Exception {
        Resposta resposta = enviar(corpoValido(camposPapel));

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.corpo()).containsOnlyKeys("dados", "erro");
        assertThat(resposta.corpo().get("erro")).isNull();
        Map<String, Object> usuario = dados(resposta);
        assertThat(usuario).containsOnlyKeys("id", "nome", "email", "papel", "perfil", "imagem");
        UUID id = UUID.fromString((String) usuario.get("id"));
        assertThat(usuario).containsEntry("nome", "Maria  Silva").containsEntry("email", EMAIL)
                .containsEntry("papel", papelEsperado).containsEntry("perfil", papelEsperado).containsEntry("imagem", "");
        assertThat(jdbc.queryForObject("select nome from contas where id = ?", String.class, id)).isEqualTo("Maria  Silva");
        assertThat(jdbc.queryForObject("select email from contas where id = ?", String.class, id)).isEqualTo(EMAIL);
        assertThat(jdbc.queryForObject("select papel from contas where id = ?", String.class, id)).isEqualTo(papelEsperado);
        assertThat(jdbc.queryForObject("select troca_senha_obrigatoria from contas where id = ?", Boolean.class, id)).isFalse();
        String hash = jdbc.queryForObject("select senha_hash from contas where id = ?", String.class, id);
        assertThat(hash).matches("\\{bcrypt\\}\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}");
        assertThat(passwordEncoder.matches(SENHA, hash)).isTrue();
        assertThat(passwordEncoder.matches(SENHA.strip(), hash)).isFalse();
        assertSemCredenciais(resposta);
        assertThat(quantidadeContas()).isEqualTo(1);
        assertControleIntacto();
    }

    @ParameterizedTest(name = "perfil manipulado HTTP [{index}]")
    @MethodSource("papeisInvalidos")
    void rejeitaPapeisForjadosAmbiguosNulosOuNaoTextuais(String campos) throws Exception {
        Resposta resposta = enviar(corpoValido(campos));

        assertErro(resposta, 400, "REQUISICAO_INVALIDA");
        assertThat(quantidadeContas()).isZero();
        assertControleIntacto();
    }

    @ParameterizedTest(name = "chave duplicada HTTP [{index}]")
    @ValueSource(strings = {
            "\"perfil\":\"admin\",\"perfil\":\"comprador\"",
            "\"perfil\":\"comprador\",\"perfil\":\"admin\"",
            "\"papel\":\"organizador\",\"papel\":\"artesao\"",
            "\"papel\":\"artesao\",\"papel\":\"organizador\"",
            "\"perfil\":null,\"perfil\":\"comprador\"",
            "\"perfil\":\"admin\",\"papel\":\"comprador\",\"perfil\":\"comprador\"",
            "\"perfil\":\"admin\",\"per\\u0066il\":\"comprador\""
    })
    void jsonBrutoComChaveDuplicadaNaoEscondeTentativaPrivilegiada(String campos) throws Exception {
        assertErro(enviar(corpoValido(campos)), 400, "REQUISICAO_INVALIDA");
        assertThat(quantidadeContas()).isZero();
        assertControleIntacto();
    }

    @ParameterizedTest(name = "dados invalidos HTTP [{index}]")
    @MethodSource("dadosInvalidos")
    void rejeitaDadosInvalidosSemGravar(String campo, Object valor) throws Exception {
        Map<String, Object> corpo = corpoBase();
        corpo.put(campo, valor);

        assertErro(enviar(JSON.writeValueAsString(corpo)), 400, "REQUISICAO_INVALIDA");
        assertThat(quantidadeContas()).isZero();
        assertControleIntacto();
    }

    @ParameterizedTest
    @ValueSource(strings = { "nome", "email", "senha" })
    void rejeitaCamposObrigatoriosAusentes(String campo) throws Exception {
        Map<String, Object> corpo = corpoBase();
        corpo.remove(campo);

        assertErro(enviar(JSON.writeValueAsString(corpo)), 400, "REQUISICAO_INVALIDA");
        assertThat(quantidadeContas()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "null", "[]", "{", "{\"nome\":}", "true", "42", "\"texto\"" })
    void corpoMalformadoOuEstruturaIncompativelMantemEnvelope(String corpo) throws Exception {
        assertErro(enviar(corpo), 400, "REQUISICAO_INVALIDA");
        assertThat(quantidadeContas()).isZero();
        assertControleIntacto();
    }

    @Test
    void rejeitaSurrogateIsoladoTransmitidoComoEscapeJsonLiteral() throws Exception {
        // O escape ASCII atravessa o UTF-8 do HttpClient sem substituir o surrogate por '?'.
        String corpo = "{\"nome\":\"Maria Silva\",\"email\":\"maria.http@example.test\","
                + "\"senha\":\"aaaaaaaaaaaa\\ud800\"}";

        assertErro(enviar(corpo), 400, "REQUISICAO_INVALIDA");
        assertThat(quantidadeContas()).isZero();
        assertControleIntacto();
    }

    @Test
    void emailDuplicadoRetorna409SemSobrescreverConta() throws Exception {
        Resposta primeira = enviar(corpoValido("\"perfil\":\"artesao\""));
        assertThat(primeira.status()).isEqualTo(201);
        UUID id = UUID.fromString((String) dados(primeira).get("id"));
        Map<String, Object> original = jdbc.queryForMap("select * from contas where id = ?", id);
        Map<String, Object> tentativa = corpoBase();
        tentativa.put("nome", "Tentativa de sobrescrita");
        tentativa.put("senha", "Outra senha de fixture 2026");
        tentativa.put("perfil", "comprador");

        assertErro(enviar(JSON.writeValueAsString(tentativa)), 409, "EMAIL_EM_USO");
        assertThat(jdbc.queryForMap("select * from contas where id = ?", id)).isEqualTo(original);
        assertThat(quantidadeContas()).isEqualTo(1);
        assertControleIntacto();
    }

    @ParameterizedTest
    @MethodSource("senhasNosLimites")
    void httpPreservaPoliticaDeCodepointsEBytesUtf8(String senha) throws Exception {
        Map<String, Object> corpo = corpoBase();
        corpo.put("senha", senha);

        Resposta resposta = enviar(JSON.writeValueAsString(corpo));

        assertThat(resposta.status()).isEqualTo(201);
        String hash = jdbc.queryForObject("select senha_hash from contas", String.class);
        assertThat(passwordEncoder.matches(senha, hash)).isTrue();
        assertSemCredenciais(resposta);
        assertThat(resposta.texto()).doesNotContain(senha);
    }

    @Test
    void falhaInternaMantemEnvelopeSanitizadoSemPersistirConta() throws Exception {
        String segredoDaFalha = "hash-ou-senha-de-fixture-na-causa";
        doThrow(new IllegalStateException(segredoDaFalha)).when(codificador).codificar(SENHA);
        try {
            Resposta resposta = enviar(corpoValido("\"perfil\":\"comprador\""));
            assertErro(resposta, 500, "CADASTRO_INDISPONIVEL");
            assertThat(resposta.texto()).doesNotContain(segredoDaFalha);
            assertThat(quantidadeContas()).isZero();
            assertControleIntacto();
        } finally {
            reset(codificador);
        }
    }

    @Test
    void cadastrosHttpConcorrentesAposPrecheckRetornam201E409() throws Exception {
        CountDownLatch consultasConcluidas = new CountDownLatch(2);
        CountDownLatch liberarCodificacao = new CountDownLatch(1);
        doAnswer(chamada -> {
            consultasConcluidas.countDown();
            if (!liberarCodificacao.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Barreira HTTP de cadastro expirou");
            }
            return chamada.callRealMethod();
        }).when(codificador).codificar(SENHA);

        List<Resposta> respostas;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var comprador = executor.submit(() -> enviar(corpoValido("\"perfil\":\"comprador\"")));
            var artesao = executor.submit(() -> enviar(corpoValido("\"papel\":\"artesao\"")));
            try {
                assertThat(consultasConcluidas.await(15, TimeUnit.SECONDS)).isTrue();
                liberarCodificacao.countDown();
                respostas = List.of(comprador.get(45, TimeUnit.SECONDS), artesao.get(45, TimeUnit.SECONDS));
            } finally {
                liberarCodificacao.countDown();
            }
        } finally {
            liberarCodificacao.countDown();
            reset(codificador);
        }

        assertThat(respostas).extracting(Resposta::status).containsExactlyInAnyOrder(201, 409);
        Resposta conflito = respostas.stream().filter(resposta -> resposta.status() == 409).findFirst().orElseThrow();
        assertErro(conflito, 409, "EMAIL_EM_USO");
        Resposta sucesso = respostas.stream().filter(resposta -> resposta.status() == 201).findFirst().orElseThrow();
        assertSemCredenciais(sucesso);
        assertThat(quantidadeContas()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select papel from contas", String.class)).isEqualTo(dados(sucesso).get("papel"));
        assertControleIntacto();
    }

    private Resposta enviar(String corpo) throws Exception {
        HttpRequest requisicao = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + "/api/v1/auth/cadastro"))
                .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo, StandardCharsets.UTF_8)).build();
        Instant antes = Instant.now();
        HttpResponse<String> resposta = cliente.send(requisicao, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Instant depois = Instant.now();
        if (resposta.statusCode() == 201) {
            assertThat(resposta.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        }
        assertThat(resposta.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        @SuppressWarnings("unchecked")
        Map<String, Object> json = JSON.readValue(resposta.body(), Map.class);
        return new Resposta(resposta.statusCode(), json, resposta.body(), resposta.headers().allValues("Set-Cookie"), antes, depois);
    }

    private void assertErro(Resposta resposta, int status, String codigo) throws Exception {
        assertThat(resposta.status()).isEqualTo(status);
        assertThat(resposta.corpo()).containsOnlyKeys("dados", "erro").containsEntry("dados", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> erro = (Map<String, Object>) resposta.corpo().get("erro");
        assertThat(erro).containsOnlyKeys("codigo", "mensagem").containsEntry("codigo", codigo);
        assertThat(erro.get("mensagem")).isInstanceOf(String.class);
        assertThat((String) erro.get("mensagem")).isNotBlank();
        assertSemCredenciais(resposta);
    }

    private void assertSemCredenciais(Resposta resposta) throws Exception {
        if (resposta.status() == 201) {
            assertThat(resposta.cookies()).hasSize(1);
            String token = AutenticacaoTestFixtures.assertCookie(resposta.cookies().getFirst(), true, 3600);
            AutenticacaoTestFixtures.assertJwt(token, UUID.fromString((String) dados(resposta).get("id")),
                    (String) dados(resposta).get("papel"), resposta.antes(), resposta.depois());
        } else {
            assertThat(resposta.cookies()).isEmpty();
        }
        assertThat(resposta.texto()).doesNotContain(SENHA, "{bcrypt}", "senhaHash", "senha_hash", "senhaSal",
                "senhaProtegida", "\"senha\"", "\"token\"", "\"jwt\"", "stackTrace", "java.lang.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dados(Resposta resposta) {
        return (Map<String, Object>) resposta.corpo().get("dados");
    }

    private long quantidadeContas() {
        return jdbc.queryForObject("select count(*) from contas", Long.class);
    }

    private void assertControleIntacto() {
        assertThat(jdbc.queryForObject("select bootstrap_concluido from controle_administracao where id = 1", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("select count(*) from contas where papel not in ('comprador', 'artesao')", Long.class))
                .isZero();
    }

    private static Map<String, Object> corpoBase() {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("nome", " \tMaria  Silva\n");
        corpo.put("email", "  MARIA.HTTP@EXAMPLE.TEST  ");
        corpo.put("senha", SENHA);
        return corpo;
    }

    private static String corpoValido(String campos) {
        String base = JSON.writeValueAsString(corpoBase());
        return campos.isEmpty() ? base : base.substring(0, base.length() - 1) + "," + campos + "}";
    }

    private static Stream<Arguments> papeisPublicos() {
        return Stream.of(
                Arguments.of("", "comprador"),
                Arguments.of("\"perfil\":\"comprador\"", "comprador"),
                Arguments.of("\"perfil\":\"artesao\"", "artesao"),
                Arguments.of("\"papel\":\"comprador\"", "comprador"),
                Arguments.of("\"papel\":\"artesao\"", "artesao"),
                Arguments.of("\"perfil\":\"comprador\",\"papel\":\"comprador\"", "comprador"),
                Arguments.of("\"perfil\":\"artesao\",\"papel\":\"artesao\"", "artesao"));
    }

    private static Stream<Arguments> papeisInvalidos() {
        Stream<String> individuais = Stream.of("perfil", "papel").flatMap(campo -> Stream.of(
                "\"admin\"", "\"organizador\"", "\"ADMIN\"", "\" admin \"", "\"ARTESAO\"", "\" artesao \"",
                "\"\"", "\" \"", "\"ROLE_ADMIN\"", "null", "true", "42", "[]", "{}",
                "[\"comprador\"]", "{\"papel\":\"comprador\"}").map(valor -> "\"" + campo + "\":" + valor));
        Stream<String> pares = Stream.of(
                "\"perfil\":\"comprador\",\"papel\":\"artesao\"",
                "\"perfil\":\"artesao\",\"papel\":\"comprador\"",
                "\"perfil\":\"admin\",\"papel\":\"comprador\"",
                "\"perfil\":\"comprador\",\"papel\":\"admin\"",
                "\"perfil\":\"organizador\",\"papel\":\"artesao\"",
                "\"perfil\":\"artesao\",\"papel\":\"organizador\"",
                "\"perfil\":\"admin\",\"papel\":\"admin\"",
                "\"perfil\":null,\"papel\":\"comprador\"",
                "\"perfil\":\"comprador\",\"papel\":null",
                "\"perfil\":null,\"papel\":null");
        return Stream.concat(individuais, pares).map(Arguments::of);
    }

    private static Stream<Arguments> dadosInvalidos() {
        return Stream.of(
                Arguments.of("nome", null), Arguments.of("nome", " \t\n"), Arguments.of("nome", "a".repeat(241)),
                Arguments.of("nome", 42), Arguments.of("nome", List.of("Maria")),
                Arguments.of("email", null), Arguments.of("email", "email-invalido"),
                Arguments.of("email", "a".repeat(250) + "@example.test"), Arguments.of("email", true),
                Arguments.of("senha", null), Arguments.of("senha", " ".repeat(12)),
                Arguments.of("senha", "a".repeat(11)), Arguments.of("senha", "a".repeat(73)),
                Arguments.of("senha", "\uD83D\uDD12".repeat(11)), Arguments.of("senha", "é".repeat(37)),
                Arguments.of("senha", "Aa9!senha-com\ncontrole"),
                Arguments.of("senha", 123456789012L), Arguments.of("senha", List.of("senha de fixture")));
    }

    private static Stream<String> senhasNosLimites() {
        return Stream.of("a".repeat(12), "a".repeat(72), "é".repeat(36), "\uD83D\uDD12".repeat(12));
    }

    private record Resposta(int status, Map<String, Object> corpo, String texto, List<String> cookies, Instant antes, Instant depois) {
    }
}
