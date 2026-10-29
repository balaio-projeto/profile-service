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
import java.util.List;
import java.util.Map;
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

import br.com.balaio.profile.AutenticacaoTestFixtures;
import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.SessaoJwtTestFixtures;
import br.com.balaio.profile.account.application.CadastrarContaPublicaComando;
import br.com.balaio.profile.account.application.port.in.CadastrarContaPublicaUseCase;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.account.security.EmissorJwtAdapter;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
class UsuarioAtualHttpIntegrationTests {
    private static final Instant AGORA = Instant.parse("2026-10-28T12:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
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

    @ParameterizedTest
    @ValueSource(strings = { "comprador", "artesao", "admin" })
    void retornaDadosEPapelAtuaisDoPostgresMesmoQuandoTokenTemPapelAnterior(String papel) throws Exception {
        var conta = cadastrar.cadastrar(new CadastrarContaPublicaComando(
                "Nome inicial", "original@example.test", SENHA, "comprador"));
        String token = emissor.emitir(conta);
        jdbc.update("update contas set nome = ?, email = ?, papel = ?, troca_senha_obrigatoria = ? where id = ?",
                "Nome atualizado", "atual@example.test", papel, papel.equals("admin"), conta.id());

        Resposta resposta = consultar(Map.of("Authorization", "Bearer " + token,
                "X-User-Id", UUID.randomUUID().toString(), "X-User-Role", "admin",
                "X-Authenticated-User-Id", UUID.randomUUID().toString()));

        assertThat(resposta.status()).isEqualTo(200);
        assertThat(resposta.corpo()).containsOnlyKeys("dados", "erro").containsEntry("erro", null);
        Map<String, Object> dados = dados(resposta);
        assertThat(dados).containsOnlyKeys(papel.equals("admin")
                ? new String[] { "id", "nome", "email", "papel", "imagem" }
                : new String[] { "id", "nome", "email", "papel", "perfil", "imagem" })
                .containsEntry("id", conta.id().toString()).containsEntry("nome", "Nome atualizado")
                .containsEntry("email", "atual@example.test").containsEntry("papel", papel).containsEntry("imagem", "");
        if (!papel.equals("admin")) {
            assertThat(dados).containsEntry("perfil", papel);
        }
        assertThat(resposta.cookies()).isEmpty();
        assertThat(resposta.texto()).doesNotContain(token, SENHA, "senha", "hash", "trocaSenhaObrigatoria", "jti");
        assertThat(jdbc.queryForObject("select count(*) from contas", Long.class)).isEqualTo(1);
    }

    @Test
    void headersDeIdentidadeSozinhosECookieDiretoNaoAutenticam() throws Exception {
        UUID id = criarConta();
        assertNaoAutenticado(consultar(Map.of()));
        assertNaoAutenticado(consultar(Map.of("X-User-Id", id.toString(), "X-User-Role", "admin")));
        assertNaoAutenticado(consultar(Map.of("Cookie", "balaio_sessao=" + token(id),
                "Origin", AutenticacaoTestFixtures.ORIGIN)));
    }

    @Test
    void contaExcluidaOuSubAusenteDoBancoRetornaOMesmo401() throws Exception {
        UUID id = criarConta();
        String token = token(id);
        jdbc.update("delete from contas where id = ?", id);
        Resposta excluida = consultar(Map.of("Authorization", "Bearer " + token));
        Resposta ausente = consultar(Map.of("Authorization", "Bearer " + token(UUID.randomUUID())));
        assertNaoAutenticado(excluida);
        assertNaoAutenticado(ausente);
        assertThat(excluida.corpo()).isEqualTo(ausente.corpo());
    }

    @ParameterizedTest
    @ValueSource(strings = { "expirado", "exp-no-limite", "assinatura", "issuer", "audience", "papel",
            "iat-futuro", "ttl-longo", "claim-ausente", "chave-remota", "algoritmo", "malformado" })
    void jwtInvalidoRetorna401SanitizadoSemSessao(String caso) throws Exception {
        UUID id = criarConta();
        var claims = SessaoJwtTestFixtures.claims(id, AGORA);
        String cabecalho = "{\"alg\":\"RS256\"}";
        switch (caso) {
            case "expirado" -> {
                claims.put("iat", AGORA.minusSeconds(3601).getEpochSecond());
                claims.put("exp", AGORA.minusSeconds(1).getEpochSecond());
            }
            case "exp-no-limite" -> {
                claims.put("iat", AGORA.minusSeconds(3600).getEpochSecond());
                claims.put("exp", AGORA.getEpochSecond());
            }
            case "issuer" -> claims.put("iss", "outro-emissor");
            case "audience" -> claims.put("aud", List.of("outra-audiencia"));
            case "papel" -> claims.put("papel", "papel-forjado");
            case "iat-futuro" -> claims.put("iat", AGORA.plusSeconds(31).getEpochSecond());
            case "ttl-longo" -> claims.put("exp", AGORA.plusSeconds(3601).getEpochSecond());
            case "claim-ausente" -> claims.remove("jti");
            case "chave-remota" -> cabecalho = "{\"alg\":\"RS256\",\"jku\":\"https://attacker.example.test/key\"}";
            case "algoritmo" -> cabecalho = "{\"alg\":\"HS256\"}";
            default -> { }
        }
        String token = SessaoJwtTestFixtures.assinar(cabecalho, JSON.writeValueAsString(claims));
        if (caso.equals("assinatura")) {
            token = SessaoJwtTestFixtures.assinar(cabecalho, JSON.writeValueAsString(claims),
                    AutenticacaoTestFixtures.gerarRsa(3072).getPrivate());
        } else if (caso.equals("malformado")) {
            token = "fixture.sem-assinatura.invalida";
        }

        Resposta resposta = consultar(Map.of("Authorization", "Bearer " + token));
        assertNaoAutenticado(resposta);
        assertThat(resposta.texto()).doesNotContain(token, "attacker", "Exception", "claims", "JWT", "senha", "hash");
        assertThat(jdbc.queryForObject("select count(*) from contas", Long.class)).isEqualTo(1);
    }

    @Test
    void authorizationDuplicadoOuComEsquemaInvalidoNaoUsaCookieComoFallback() throws Exception {
        UUID id = criarConta();
        String token = token(id);
        assertNaoAutenticado(consultar(Map.of("Authorization", "Basic fixture", "Cookie", "balaio_sessao=" + token)));
        var requisicao = requisicao().header("Authorization", "Bearer " + token)
                .header("Authorization", "Bearer " + token).build();
        assertNaoAutenticado(enviar(requisicao));
    }

    private UUID criarConta() {
        return cadastrar.cadastrar(new CadastrarContaPublicaComando(
                "Pessoa de teste", "usuario@example.test", SENHA, "comprador")).id();
    }

    private String token(UUID id) {
        return SessaoJwtTestFixtures.token(SessaoJwtTestFixtures.claims(id, AGORA));
    }

    private Resposta consultar(Map<String, String> headers) throws Exception {
        var requisicao = requisicao();
        headers.forEach(requisicao::header);
        return enviar(requisicao.build());
    }

    private HttpRequest.Builder requisicao() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + "/api/v1/usuario"))
                .timeout(Duration.ofSeconds(30)).GET();
    }

    private Resposta enviar(HttpRequest requisicao) throws Exception {
        HttpResponse<String> resposta = cliente.send(requisicao, HttpResponse.BodyHandlers.ofString());
        assertThat(resposta.headers().firstValue("Cache-Control")).hasValue("no-store");
        @SuppressWarnings("unchecked")
        Map<String, Object> corpo = JSON.readValue(resposta.body(), Map.class);
        return new Resposta(resposta.statusCode(), corpo, resposta.body(), resposta.headers().allValues("Set-Cookie"));
    }

    private void assertNaoAutenticado(Resposta resposta) {
        assertThat(resposta.status()).isEqualTo(401);
        assertThat(resposta.corpo()).containsOnlyKeys("dados", "erro").containsEntry("dados", null);
        assertThat(resposta.corpo().get("erro")).isEqualTo(Map.of(
                "codigo", "NAO_AUTENTICADO", "mensagem", "Autenticação necessária."));
        assertThat(resposta.cookies()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dados(Resposta resposta) {
        return (Map<String, Object>) resposta.corpo().get("dados");
    }

    private record Resposta(int status, Map<String, Object> corpo, String texto, List<String> cookies) {
    }
}
