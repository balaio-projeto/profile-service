package br.com.balaio.profile.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
class NotificacoesHttpTest {
    private static final String TOKEN_INTERNO = "token-interno-de-teste-1234567890ab";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HASH = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("SenhaDeTeste123!");

    @LocalServerPort
    private int porta;
    @MockitoBean(enforceOverride = true)
    private InicializarAdministradorUseCase inicializarAdministrador;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LimpezaNotificacoes limpeza;

    private final HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private UUID comprador;
    private UUID outro;

    @BeforeEach
    void preparar() {
        comprador = conta();
        outro = conta();
    }

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM contas WHERE id IN (?,?)", comprador, outro);
    }

    @Test
    void registroInternoExigeTokenEhIdempotentePelaChave() throws Exception {
        var corpo = Map.of("conta", comprador.toString(), "tipo", "pagamento_confirmado",
                "titulo", "Pagamento confirmado", "mensagem", "O pagamento do pedido ABC foi confirmado.",
                "link", "/orders/abc", "chave", "pedido:abc:pagamento_confirmado:comprador");
        assertThat(interno(corpo, null).statusCode()).isEqualTo(403);
        assertThat(interno(corpo, "token-incorreto-de-teste-1234567890ab").statusCode()).isEqualTo(403);

        var primeira = interno(corpo, TOKEN_INTERNO);
        var repetida = interno(corpo, TOKEN_INTERNO);
        assertThat(primeira.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(primeira.body()).path("dados").path("criada").asBoolean()).isTrue();
        assertThat(JSON.readTree(repetida.body()).path("dados").path("criada").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notificacoes WHERE conta_id = ?", Integer.class, comprador))
                .isEqualTo(1);
    }

    @Test
    void registroInternoRecusaDadosInvalidosEContaInexistente() throws Exception {
        assertThat(interno(Map.of("conta", "nao-e-uuid", "tipo", "x_y", "titulo", "T", "mensagem", "M"), TOKEN_INTERNO)
                .statusCode()).isEqualTo(400);
        assertThat(interno(Map.of("conta", comprador.toString(), "tipo", "Tipo Invalido", "titulo", "T", "mensagem", "M"),
                TOKEN_INTERNO).statusCode()).isEqualTo(400);
        assertThat(interno(Map.of("conta", comprador.toString(), "tipo", "pedido", "titulo", "T", "mensagem", "M",
                "link", "https://externo.example"), TOKEN_INTERNO).statusCode()).isEqualTo(400);
        assertThat(interno(Map.of("conta", UUID.randomUUID().toString(), "tipo", "pedido", "titulo", "T", "mensagem", "M"),
                TOKEN_INTERNO).statusCode()).isEqualTo(404);
    }

    @Test
    void caixaListaSoAsDaContaEMarcaComoLidas() throws Exception {
        registrar(comprador, "primeira");
        registrar(comprador, "segunda");
        registrar(outro, "alheia");

        assertThat(chamar("GET", "/api/v1/notificacoes", null).statusCode()).isEqualTo(401);
        var caixa = JSON.readTree(chamar("GET", "/api/v1/notificacoes", comprador).body()).path("dados");
        assertThat(caixa.path("naoLidas").asInt()).isEqualTo(2);
        assertThat(caixa.path("itens")).hasSize(2);
        assertThat(caixa.toString()).doesNotContain("alheia");

        String id = caixa.path("itens").get(0).path("id").asString();
        assertThat(chamar("PATCH", "/api/v1/notificacoes/" + id + "/leitura", outro).statusCode()).isEqualTo(404);
        var depoisDeUma = JSON.readTree(chamar("PATCH", "/api/v1/notificacoes/" + id + "/leitura", comprador).body());
        assertThat(depoisDeUma.path("dados").path("naoLidas").asInt()).isEqualTo(1);

        var todas = JSON.readTree(chamar("POST", "/api/v1/notificacoes/leitura", comprador).body()).path("dados");
        assertThat(todas.path("naoLidas").asInt()).isZero();
        for (JsonNode item : todas.path("itens")) assertThat(item.path("lida").asBoolean()).isTrue();
        assertThat(JSON.readTree(chamar("GET", "/api/v1/notificacoes", outro).body()).path("dados").path("naoLidas").asInt())
                .isEqualTo(1);
    }

    @Test
    void lidaSomeVinteEQuatroHorasDepoisENaoLidaFicaAteSerLida() throws Exception {
        registrar(comprador, "lida-ontem");
        registrar(comprador, "lida-agora");
        registrar(comprador, "nao-lida-antiga");
        jdbc.update("UPDATE notificacoes SET lida_em = now() - interval '25 hours' WHERE conta_id = ? AND titulo = 'lida-ontem'",
                comprador);
        jdbc.update("UPDATE notificacoes SET lida_em = now() - interval '23 hours' WHERE conta_id = ? AND titulo = 'lida-agora'",
                comprador);
        jdbc.update("UPDATE notificacoes SET criada_em = now() - interval '30 days' WHERE conta_id = ? "
                + "AND titulo = 'nao-lida-antiga'", comprador);

        var caixa = JSON.readTree(chamar("GET", "/api/v1/notificacoes", comprador).body()).path("dados");
        assertThat(caixa.toString()).contains("lida-agora", "nao-lida-antiga").doesNotContain("lida-ontem");
        assertThat(caixa.path("naoLidas").asInt()).isEqualTo(1);

        limpeza.removerLidasAntigas();
        assertThat(jdbc.queryForList("SELECT titulo FROM notificacoes WHERE conta_id = ? ORDER BY titulo", String.class,
                comprador)).containsExactly("lida-agora", "nao-lida-antiga");
    }

    @Test
    void naoLidasNaoTemLimiteDeQuantidade() throws Exception {
        for (int i = 0; i < 40; i++) registrar(comprador, "aviso-" + i);
        var caixa = JSON.readTree(chamar("GET", "/api/v1/notificacoes", comprador).body()).path("dados");
        assertThat(caixa.path("itens")).hasSize(40);
        assertThat(caixa.path("naoLidas").asInt()).isEqualTo(40);
    }

    private void registrar(UUID conta, String titulo) throws Exception {
        var resposta = interno(Map.of("conta", conta.toString(), "tipo", "pedido", "titulo", titulo,
                "mensagem", "Mensagem de " + titulo, "link", "/orders"), TOKEN_INTERNO);
        assertThat(resposta.statusCode()).isEqualTo(200);
    }

    private UUID conta() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO contas(id,nome,email,senha_hash,papel) VALUES (?,?,?,?,?)",
                id, "Conta teste", id + "@example.test", HASH, "comprador");
        return id;
    }

    private HttpResponse<String> interno(Map<String, String> corpo, String token) throws Exception {
        var requisicao = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/internal/v1/notificacoes"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(corpo)));
        if (token != null) requisicao.header("X-Balaio-Service-Token", token);
        return cliente.send(requisicao.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> chamar(String metodo, String caminho, UUID conta) throws Exception {
        var requisicao = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
                .timeout(Duration.ofSeconds(10)).method(metodo, HttpRequest.BodyPublishers.noBody());
        if (conta != null) {
            var claims = SessaoJwtTestFixtures.claims(conta, Instant.now());
            claims.put("papel", "comprador");
            requisicao.header("Authorization", "Bearer " + SessaoJwtTestFixtures.token(claims));
        }
        return cliente.send(requisicao.build(), HttpResponse.BodyHandlers.ofString());
    }
}
