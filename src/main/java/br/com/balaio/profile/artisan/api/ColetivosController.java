package br.com.balaio.profile.artisan.api;

import br.com.balaio.profile.account.api.CadastroPublicoResposta;
import br.com.balaio.profile.account.security.ValidarSessaoAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/coletivos")
public class ColetivosController {
    public record Coletivo(String slug, String nome, String territorio, String fundado, String historia,
            List<String> membros, List<String> tecnicas, List<String> apoio, String imagem,
            List<JsonNode> pecas) {}
    public record NovoColetivo(String slug, String nome, String territorio, String fundado,
            String historia, String imagem, List<String> apoio, List<String> membros) {}

    private final JdbcTemplate jdbc;
    private final ValidarSessaoAdapter sessoes;
    private final String catalogo;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public ColetivosController(JdbcTemplate jdbc, ValidarSessaoAdapter sessoes,
            @Value("${balaio.catalog.url}") String catalogo) {
        this.jdbc = jdbc;
        this.sessoes = sessoes;
        this.catalogo = catalogo.replaceAll("/+$", "");
    }

    private List<String> membros(UUID id) {
        return jdbc.query("SELECT p.slug FROM coletivo_membros m JOIN perfis_artesao p ON p.id = m.perfil_id WHERE m.coletivo_id = ? ORDER BY p.nome, p.slug",
                (rs, row) -> rs.getString(1), id);
    }

    private List<String> tecnicas(UUID id) {
        return jdbc.query("SELECT DISTINCT p.tecnica FROM coletivo_membros m JOIN perfis_artesao p ON p.id = m.perfil_id WHERE m.coletivo_id = ? AND p.tecnica <> '' ORDER BY p.tecnica",
                (rs, row) -> rs.getString(1), id);
    }

    private List<JsonNode> pecas(List<String> membros) {
        return membros.stream().flatMap(slug -> {
            try {
                String path = URLEncoder.encode(slug, StandardCharsets.UTF_8).replace("+", "%20");
                var req = HttpRequest.newBuilder(URI.create(catalogo + "/api/v1/artesaos/" + path + "/pecas"))
                        .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build();
                var resposta = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resposta.statusCode() != 200 || resposta.body().length > 8 * 1024 * 1024)
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Catálogo indisponível.");
                var envelope = json.readTree(resposta.body());
                var dados = envelope.get("dados");
                if (dados == null || !dados.isArray() || !envelope.has("erro") || !envelope.get("erro").isNull())
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Resposta inválida do catálogo.");
                return pecasAtivas(dados, slug).stream();
            } catch (ResponseStatusException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Catálogo indisponível.", e);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Catálogo indisponível.", e);
            }
        }).toList();
    }

    static List<JsonNode> pecasAtivas(JsonNode dados, String slug) {
        var itens = new java.util.ArrayList<JsonNode>();
        for (var peca : dados) {
            if (slug.equals(peca.path("artesao").asText())
                    && "publicada".equals(peca.path("situacao").asText())
                    && (peca.get("inativadoEm") == null || peca.get("inativadoEm").isNull()))
                itens.add(peca);
        }
        return List.copyOf(itens);
    }

    private Coletivo montar(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<String> integrantes = membros(id);
        String[] apoios = (String[]) rs.getArray("apoio").getArray();
        return new Coletivo(rs.getString("slug"), rs.getString("nome"), rs.getString("territorio"),
                rs.getString("fundado"), rs.getString("historia"), integrantes, tecnicas(id),
                List.of(apoios), rs.getString("imagem"), pecas(integrantes));
    }

    @GetMapping
    public CadastroPublicoResposta<List<Coletivo>> listar() {
        return new CadastroPublicoResposta<>(
                jdbc.query("SELECT * FROM coletivos ORDER BY nome, slug", (rs, row) -> montar(rs)), null);
    }

    @GetMapping("/{slug}")
    public CadastroPublicoResposta<Coletivo> consultar(@PathVariable String slug) {
        var encontrados = jdbc.query("SELECT * FROM coletivos WHERE slug = ?", (rs, row) -> montar(rs), slug);
        if (encontrados.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Coletivo não encontrado.");
        return new CadastroPublicoResposta<>(encontrados.getFirst(), null);
    }

    @PostMapping
    @Transactional
    public CadastroPublicoResposta<Coletivo> criar(@RequestBody NovoColetivo dados, HttpServletRequest request) {
        UUID conta = sessoes.validarBearer(Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION))).contaId();
        String papel = jdbc.queryForObject("SELECT papel FROM contas WHERE id = ?", String.class, conta);
        if (!"admin".equals(papel)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (dados.slug() == null || !dados.slug().matches("[a-z0-9]+(-[a-z0-9]+)*")
                || dados.nome() == null || dados.nome().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slug e nome são obrigatórios.");
        UUID id = UUID.randomUUID();
        try {
            inserirColetivo(id, dados);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slug de coletivo já existe.", e);
        }
        for (String membro : dados.membros() == null ? List.<String>of() : dados.membros().stream().distinct().toList()) {
            int inseridos = jdbc.update("INSERT INTO coletivo_membros(coletivo_id,perfil_id) SELECT ?, id FROM perfis_artesao WHERE slug = ?",
                    id, membro);
            if (inseridos != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Artesão inexistente: " + membro);
        }
        return new CadastroPublicoResposta<>(new Coletivo(dados.slug(), dados.nome().trim(),
                valor(dados.territorio()), valor(dados.fundado()), valor(dados.historia()),
                membros(id), tecnicas(id), dados.apoio() == null ? List.of() : List.copyOf(dados.apoio()),
                dados.imagem() == null ? "/fotos/ImagemBase.webp" : dados.imagem(), List.of()), null);
    }

    private void inserirColetivo(UUID id, NovoColetivo dados) {
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO coletivos(id,slug,nome,territorio,fundado,historia,imagem,apoio) VALUES (?,?,?,?,?,?,?,?)");
            statement.setObject(1, id);
            statement.setString(2, dados.slug());
            statement.setString(3, dados.nome().trim());
            statement.setString(4, valor(dados.territorio()));
            statement.setString(5, valor(dados.fundado()));
            statement.setString(6, valor(dados.historia()));
            statement.setString(7, dados.imagem() == null ? "/fotos/ImagemBase.webp" : dados.imagem());
            statement.setArray(8, connection.createArrayOf("text",
                    dados.apoio() == null ? new String[0] : dados.apoio().toArray(String[]::new)));
            return statement;
        });
    }

    private String valor(String texto) { return texto == null ? "" : texto.trim(); }
}
