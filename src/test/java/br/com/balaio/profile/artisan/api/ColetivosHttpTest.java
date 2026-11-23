package br.com.balaio.profile.artisan.api;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.SessaoJwtTestFixtures;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Coletivos via HTTP. O catálogo é substituído por um servidor falso que registra as chamadas,
 * provando que as peças chegam pela API do catálogo e não por leitura de banco.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "balaio.teste.contexto=coletivos")
@Import(PostgreSQLTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ColetivosHttpTest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String HASH = "{bcrypt}" + new BCryptPasswordEncoder(12).encode("SenhaDeTeste123!");
    static final Map<String, String> RESPOSTAS_CATALOGO = new ConcurrentHashMap<>();
    static final List<String> CHAMADAS_CATALOGO = new CopyOnWriteArrayList<>();
    static volatile boolean catalogoFalha = false;
    static final HttpServer CATALOGO = iniciarCatalogoFalso();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(enforceOverride = true) InicializarAdministradorUseCase admin;

    @DynamicPropertySource
    static void catalogo(DynamicPropertyRegistry registro) {
        registro.add("balaio.catalog.url", () -> "http://127.0.0.1:" + CATALOGO.getAddress().getPort());
    }

    @AfterAll
    static void encerrarCatalogo() {
        CATALOGO.stop(0);
    }

    @BeforeEach void limpar() {
        jdbc.update("DELETE FROM coletivos");
        jdbc.update("DELETE FROM contas");
        RESPOSTAS_CATALOGO.clear();
        CHAMADAS_CATALOGO.clear();
        catalogoFalha = false;
    }

    @Test
    void membrosVemDoPerfilEPecasVemDaApiDoCatalogo() throws Exception {
        var ana = criarArtesao("Xilogravura");
        var bia = criarArtesao("Cerâmica");
        var admin = criarConta("admin");
        String slug = "coletivo-" + UUID.randomUUID().toString().substring(0, 8);
        assertStatus(post("/api/v1/coletivos", novoColetivo(slug, List.of(ana.slug(), bia.slug())), admin), 200);

        RESPOSTAS_CATALOGO.put(caminhoPecas(ana.slug()), envelope("""
                [
                  {"slug":"ativa","artesao":"%s","situacao":"publicada","inativadoEm":null},
                  {"slug":"rascunho","artesao":"%s","situacao":"rascunho","inativadoEm":null},
                  {"slug":"inativa","artesao":"%s","situacao":"publicada","inativadoEm":"2026-01-01"}
                ]""".formatted(ana.slug(), ana.slug(), ana.slug())));
        RESPOSTAS_CATALOGO.put(caminhoPecas(bia.slug()), envelope("[]"));

        var resposta = get("/api/v1/coletivos/" + slug);
        assertStatus(resposta, 200);
        JsonNode dados = JSON.readTree(resposta.body()).get("dados");
        assertThat(dados.get("membros")).extracting(JsonNode::asString)
                .containsExactlyInAnyOrder(ana.slug(), bia.slug());
        assertThat(dados.get("tecnicas")).extracting(JsonNode::asString)
                .containsExactlyInAnyOrder("Cerâmica", "Xilogravura");
        assertThat(dados.get("pecas")).extracting(p -> p.get("slug").asString()).containsExactly("ativa");
        assertThat(CHAMADAS_CATALOGO).containsExactlyInAnyOrder(caminhoPecas(ana.slug()), caminhoPecas(bia.slug()));
    }

    @Test
    void coletivoSemPecasPublicadasDevolveListaVazia() throws Exception {
        var bia = criarArtesao("Cerâmica");
        var admin = criarConta("admin");
        String slug = "vazio-" + UUID.randomUUID().toString().substring(0, 8);
        assertStatus(post("/api/v1/coletivos", novoColetivo(slug, List.of(bia.slug())), admin), 200);
        RESPOSTAS_CATALOGO.put(caminhoPecas(bia.slug()), envelope("[]"));

        var resposta = get("/api/v1/coletivos/" + slug);
        assertStatus(resposta, 200);
        assertThat(JSON.readTree(resposta.body()).get("dados").get("pecas").size()).isZero();
    }

    @Test
    void catalogoIndisponivelNaoInventaPecas() throws Exception {
        var ana = criarArtesao("Xilogravura");
        var admin = criarConta("admin");
        String slug = "falha-" + UUID.randomUUID().toString().substring(0, 8);
        assertStatus(post("/api/v1/coletivos", novoColetivo(slug, List.of(ana.slug())), admin), 200);
        catalogoFalha = true;

        var resposta = get("/api/v1/coletivos/" + slug);
        assertStatus(resposta, 502);
        assertThat(resposta.body()).doesNotContain("\"pecas\"");
    }

    @Test
    void slugDeColetivoDuplicadoRetornaConflito() throws Exception {
        var admin = criarConta("admin");
        String slug = "dup-" + UUID.randomUUID().toString().substring(0, 8);
        assertStatus(post("/api/v1/coletivos", novoColetivo(slug, List.of()), admin), 200);
        assertStatus(post("/api/v1/coletivos", novoColetivo(slug, List.of()), admin), 409);
    }

    @Test
    void membroRepetidoNaoDuplicaVinculo() throws Exception {
        var ana = criarArtesao("Xilogravura");
        var admin = criarConta("admin");
        String slug = "repetido-" + UUID.randomUUID().toString().substring(0, 8);
        assertStatus(post("/api/v1/coletivos", novoColetivo(slug, List.of(ana.slug(), ana.slug())), admin), 200);
        RESPOSTAS_CATALOGO.put(caminhoPecas(ana.slug()), envelope("[]"));

        var dados = JSON.readTree(get("/api/v1/coletivos/" + slug).body()).get("dados");
        assertThat(dados.get("membros")).hasSize(1);
    }

    @Test
    void membroInexistenteEhRecusado() throws Exception {
        var admin = criarConta("admin");
        assertStatus(post("/api/v1/coletivos",
                novoColetivo("inexistente-" + UUID.randomUUID().toString().substring(0, 8), List.of("nao-existe")), admin), 400);
    }

    @Test
    void apenasAdministradorCriaColetivo() throws Exception {
        var comprador = criarConta("comprador");
        assertStatus(post("/api/v1/coletivos", novoColetivo("nao-admin", List.of()), comprador), 403);
        assertStatus(post("/api/v1/coletivos", novoColetivo("sem-sessao", List.of()), null), 401);
    }

    @Test
    void coletivoInexistenteRetorna404() throws Exception {
        assertStatus(get("/api/v1/coletivos/nao-existe"), 404);
    }


    private record Artesao(UUID conta, String slug) {}

    private Artesao criarArtesao(String tecnica) {
        UUID conta = UUID.randomUUID();
        String slug = "artesao-" + conta;
        jdbc.update("INSERT INTO contas(id,nome,email,senha_hash,papel) VALUES (?,?,?,?,?)",
                conta, "Artesão teste", conta + "@example.test", HASH, "artesao");
        jdbc.update("INSERT INTO perfis_artesao(id,conta_id,slug,nome,atelie,historia,territorio,tecnica) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), conta, slug, "Nome público", "Ateliê", "História", "Pajeú", tecnica);
        return new Artesao(conta, slug);
    }

    private UUID criarConta(String papel) {
        UUID conta = UUID.randomUUID();
        jdbc.update("INSERT INTO contas(id,nome,email,senha_hash,papel) VALUES (?,?,?,?,?)",
                conta, "Conta teste", conta + "@example.test", HASH, papel);
        return conta;
    }

    private String novoColetivo(String slug, List<String> membros) throws Exception {
        return JSON.writeValueAsString(Map.of("slug", slug, "nome", "Coletivo " + slug, "territorio", "Pajeú",
                "fundado", "2024", "historia", "História", "apoio", List.of("Edital"), "membros", membros));
    }

    private static String caminhoPecas(String slug) {
        return "/api/v1/artesaos/" + slug + "/pecas";
    }

    private static String envelope(String dados) {
        return "{\"dados\":" + dados + ",\"erro\":null}";
    }

    private HttpResponse<String> get(String caminho) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri(caminho)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String caminho, String corpo, UUID conta) throws Exception {
        var req = HttpRequest.newBuilder(uri(caminho)).header("Content-Type", "application/json");
        if (conta != null) req.header("Authorization", "Bearer " + token(conta));
        return HTTP.send(req.POST(HttpRequest.BodyPublishers.ofString(corpo)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String token(UUID conta) {
        var claims = SessaoJwtTestFixtures.claims(conta, Instant.now());
        return SessaoJwtTestFixtures.token(claims);
    }

    private URI uri(String caminho) {
        return URI.create("http://localhost:" + port + caminho);
    }

    private static void assertStatus(HttpResponse<String> resposta, int status) {
        assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(status);
    }

    private static HttpServer iniciarCatalogoFalso() {
        try {
            var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/", troca -> {
                String caminho = troca.getRequestURI().getPath();
                CHAMADAS_CATALOGO.add(caminho);
                if (catalogoFalha) {
                    troca.sendResponseHeaders(500, -1);
                    troca.close();
                    return;
                }
                byte[] corpo = RESPOSTAS_CATALOGO.getOrDefault(caminho, envelope("[]"))
                        .getBytes(StandardCharsets.UTF_8);
                troca.getResponseHeaders().set("Content-Type", "application/json");
                troca.sendResponseHeaders(200, corpo.length);
                try (var saida = troca.getResponseBody()) { saida.write(corpo); }
            });
            servidor.start();
            return servidor;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
