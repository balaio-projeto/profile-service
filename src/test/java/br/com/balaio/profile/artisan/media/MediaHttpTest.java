package br.com.balaio.profile.artisan.media;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.SessaoJwtTestFixtures;
import br.com.balaio.profile.AutenticacaoTestFixtures;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
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
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "balaio.media.cleanup-delay=3600000")
@Import(PostgreSQLTestConfiguration.class)
class MediaHttpTest {
    private static final String UPLOAD = "/api/v1/artesao/me/imagem";
    private static final String BOUNDARY = "balaio-test-boundary";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MidiaPerfilService midias;
    @MockitoBean(enforceOverride = true) InicializarAdministradorUseCase admin;
    @MockitoBean(enforceOverride = true) ArmazenamentoMidiaPort storage;
    final Map<String, byte[]> objetos = new ConcurrentHashMap<>();
    UUID conta, perfil, outraConta, outroPerfil, comprador;

    @BeforeEach void preparar() {
        conta = UUID.randomUUID(); perfil = UUID.randomUUID();
        outraConta = UUID.randomUUID(); outroPerfil = UUID.randomUUID(); comprador = UUID.randomUUID();
        criar(conta, perfil, "artesao"); criar(outraConta, outroPerfil, "artesao");
        criar(comprador, null, "comprador");
        doAnswer(call -> {
            MidiaPerfil m = call.getArgument(0);
            objetos.put(m.chave(), ((byte[]) call.getArgument(1)).clone());
            return null;
        }).when(storage).enviar(any(), any());
        when(storage.recuperar(any())).thenAnswer(call -> objetos.get(((MidiaPerfil) call.getArgument(0)).chave()));
        doAnswer(call -> { objetos.remove(((MidiaPerfil) call.getArgument(0)).chave()); return null; })
                .when(storage).excluir(any());
    }

    @AfterEach void limparFixtures() {
        // Only disposable PostgreSQL rows and the in-memory storage belong to this test.
        jdbc.update("DELETE FROM midias_perfil WHERE perfil_id IN (?,?)", perfil, outroPerfil);
        jdbc.update("DELETE FROM contas WHERE id IN (?,?,?)", conta, outraConta, comprador);
        objetos.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png", "webp"})
    void uploadValidaConteudoRealEDevolvePerfilLegado(String formato) throws Exception {
        byte[] bytes = imagem(formato);
        var resposta = upload(bytes, conta);
        assertThat(resposta.statusCode()).isEqualTo(200);
        var dados = JSON.readTree(resposta.body()).get("dados");
        assertThat(dados.get("id").asText()).isEqualTo(conta.toString());
        assertThat(dados.has("atelie")).isTrue();
        assertThat(dados.has("obrasComercializadas")).isTrue();
        String url = dados.get("imagem").asText();
        assertThat(url).startsWith(MidiaPerfil.PREFIXO_PUBLICO);
        assertThat(new String(resposta.body(), StandardCharsets.UTF_8))
                .doesNotContain("seaweedfs", "balaio-media", "profile/", "chave");
        var leitura = enviar("GET", url, null, null);
        assertThat(leitura.statusCode()).isEqualTo(200);
        assertThat(leitura.body()).isEqualTo(bytes);
        assertThat(leitura.headers().firstValue("Content-Type")).hasValue("image/" + formato);
        assertThat(leitura.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(leitura.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    @Test void substituicaoRemocaoEHeadRespeitamVinculoAtual() throws Exception {
        String antiga = url(upload(imagem("png"), conta));
        String atual = url(upload(imagem("jpeg"), conta));
        assertThat(atual).isNotEqualTo(antiga);
        assertThat(enviar("GET", antiga, null, null).statusCode()).isEqualTo(404);
        assertThat(enviar("HEAD", antiga, null, null).statusCode()).isEqualTo(404);
        clearInvocations(storage);
        var head = enviar("HEAD", atual, null, null);
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(head.headers().firstValue("Content-Length")).hasValue(String.valueOf(imagem("jpeg").length));
        verifyNoInteractions(storage);
        var removida = enviar("DELETE", UPLOAD, null, conta);
        assertThat(removida.statusCode()).isEqualTo(200);
        assertThat(url(removida)).isEqualTo(MidiaPerfil.PLACEHOLDER);
        assertThat(enviar("GET", atual, null, null).statusCode()).isEqualTo(404);
        assertThat(enviar("HEAD", atual, null, null).statusCode()).isEqualTo(404);
        verify(storage, never()).excluir(any());
        midias.limpar();
        assertThat(objetos).isEmpty();
    }

    @Test void pendenteOuSemVinculoNaoPossuiLeituraPublica() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO midias_perfil(id,perfil_id,chave,tipo,tamanho,sha256,estado) VALUES (?,?,?,'image/png',1,?,'PENDING')",
                id, perfil, "profile/diagnostics/" + id, "a".repeat(64));
        for (String metodo : new String[]{"GET", "HEAD"}) {
            assertThat(enviar(metodo, MidiaPerfil.PREFIXO_PUBLICO + id, null, null).statusCode()).isEqualTo(404);
        }
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void exigeSessaoEPapelArtesao(String metodo) throws Exception {
        byte[] body = metodo.equals("PUT") ? multipart(imagem("png")) : null;
        assertThat(enviar(metodo, UPLOAD, body, null).statusCode()).isEqualTo(401);
        assertThat(enviar(metodo, UPLOAD, body, comprador).statusCode()).isEqualTo(403);
        verifyNoInteractions(storage);
    }

    @Test void proprietarioVemDaSessaoMesmoComIdInformadoNaQuery() throws Exception {
        var response = enviar("PUT", UPLOAD + "?perfilId=" + outroPerfil,
                multipart(imagem("png")), conta);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(imagemAtual(perfil)).startsWith(MidiaPerfil.PREFIXO_PUBLICO);
        assertThat(imagemAtual(outroPerfil)).isEqualTo(MidiaPerfil.PLACEHOLDER);
    }

    @Test void cookieExigeOriginEContinuaFuncionandoComOriginValido() throws Exception {
        String cookie = "balaio_sessao=" + token(conta);
        assertThat(enviar("PUT", UPLOAD, multipart(imagem("png")), null, "Cookie", cookie).statusCode()).isEqualTo(403);
        assertThat(enviar("PUT", UPLOAD, multipart(imagem("png")), null, "Cookie", cookie,
                "Origin", AutenticacaoTestFixtures.ORIGIN).statusCode()).isEqualTo(200);
    }

    @Test void arquivoAusenteVazioOuMultipartInvalidoRecebe400() throws Exception {
        assertErro(enviar("PUT", UPLOAD, ("--" + BOUNDARY + "--\r\n").getBytes(), conta), 400);
        assertErro(upload(new byte[0], conta), 400);
        assertErro(enviar("PUT", UPLOAD, "multipart quebrado".getBytes(), conta), 400);
        verifyNoInteractions(storage);
    }

    @Test void formatoFalsoOuNaoPermitidoRecebe415() throws Exception {
        assertErro(upload("conteudo sem imagem".getBytes(), conta), 415);
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "gif", out);
        assertErro(upload(out.toByteArray(), conta), 415);
        verifyNoInteractions(storage);
    }

    @Test void contentTypeIncorretoUsaEnvelopeMesmoAntesDeSelecionarController() throws Exception {
        var request = HttpRequest.newBuilder(endereco(UPLOAD)).header("Authorization", "Bearer " + token(conta))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertErro(HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray()), 415);
        verifyNoInteractions(storage);
    }

    @Test void arquivoERequisicaoAcimaDosLimitesRecebem413AntesDoStorage() throws Exception {
        assertErro(upload(new byte[ValidarImagem.MAX_BYTES + 1], conta), 413);
        assertErro(upload(new byte[3 * 1024 * 1024 + 1], conta), 413);
        verifyNoInteractions(storage);
    }

    @Test void limitesTambemValemSemContentLength() throws Exception {
        for (int tamanho : new int[]{ValidarImagem.MAX_BYTES + 1, 3 * 1024 * 1024 + 1}) {
            var body = HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofByteArray(multipart(new byte[tamanho])));
            var request = HttpRequest.newBuilder(endereco(UPLOAD)).header("Authorization", "Bearer " + token(conta))
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY).method("PUT", body).build();
            assertErro(HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray()), 413);
        }
        verifyNoInteractions(storage);
    }

    @Test void jwtInvalidoRecebe401() throws Exception {
        assertErro(enviar("DELETE", UPLOAD, null, null, "Authorization", "Bearer invalido"), 401);
    }

    @Test void indisponibilidadeNaoSubstituiImagemAnteriorNemVazaExcecao() throws Exception {
        String anterior = url(upload(imagem("png"), conta));
        doThrow(new MidiaException(MidiaException.Motivo.STORAGE_INDISPONIVEL)).when(storage).enviar(any(), any());
        assertErro(upload(imagem("jpeg"), conta), 503);
        assertThat(imagemAtual(perfil)).isEqualTo(anterior);
        assertThat(enviar("GET", anterior, null, null).statusCode()).isEqualTo(200);
    }

    @Test void conflitoRetorna409() throws Exception {
        doThrow(new MidiaException(MidiaException.Motivo.CONFLITO)).when(storage).enviar(any(), any());
        assertErro(upload(imagem("png"), conta), 409);
    }

    @Test void patchEConsultasDoPi171PreservamImagemAtual() throws Exception {
        String atual = url(upload(imagem("png"), conta));
        var request = HttpRequest.newBuilder(endereco("/api/v1/artesao/me"))
                .header("Authorization", "Bearer " + token(conta)).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"historia\":\"Historia atualizada\"}")).build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(200);
        var perfilPublico = enviar("GET", "/api/v1/artesaos/teste-" + perfil, null, null);
        assertThat(url(perfilPublico)).isEqualTo(atual);
        assertThat(url(enviar("GET", "/api/v1/artesao/me", null, conta))).isEqualTo(atual);
        assertThat(enviar("GET", atual, null, null).statusCode()).isEqualTo(200);
    }

    @Test void uploadCriaPerfilLegadoSemRegredirIdentificadorESlugDoPi171() throws Exception {
        UUID artesaoAntigo = UUID.randomUUID();
        criar(artesaoAntigo, null, "artesao");
        try {
            var resposta = upload(imagem("png"), artesaoAntigo);
            assertThat(resposta.statusCode()).isEqualTo(200);
            var dados = JSON.readTree(resposta.body()).get("dados");
            assertThat(dados.get("id").asText()).isEqualTo(artesaoAntigo.toString());
            assertThat(dados.get("slug").asText()).isEqualTo("artesao-" + artesaoAntigo);
            String imagem = dados.get("imagem").asText();
            assertThat(url(enviar("GET", "/api/v1/artesaos/artesao-" + artesaoAntigo, null, null)))
                    .isEqualTo(imagem);
            assertThat(enviar("GET", imagem, null, null).statusCode()).isEqualTo(200);
        } finally {
            jdbc.update("DELETE FROM midias_perfil WHERE perfil_id IN (SELECT id FROM perfis_artesao WHERE conta_id=?)", artesaoAntigo);
            jdbc.update("DELETE FROM contas WHERE id=?", artesaoAntigo);
        }
    }

    private void criar(UUID id, UUID perfilId, String papel) {
        jdbc.update("INSERT INTO contas(id,nome,email,senha_hash,papel) VALUES (?,?,?,?,?)",
                id, "Teste HTTP", id + "@test.invalid", "{bcrypt}$2b$12$" + ".".repeat(53), papel);
        if (perfilId != null) jdbc.update("INSERT INTO perfis_artesao(id,conta_id,slug,nome,atelie) VALUES (?,?,?,?,?)",
                perfilId, id, "teste-" + perfilId, "Teste HTTP", "Atelie HTTP");
    }
    private String imagemAtual(UUID id) {
        return jdbc.queryForObject("SELECT imagem FROM perfis_artesao WHERE id=?", String.class, id);
    }
    private HttpResponse<byte[]> upload(byte[] bytes, UUID id) throws Exception {
        return enviar("PUT", UPLOAD, multipart(bytes), id);
    }
    private byte[] multipart(byte[] bytes) throws Exception {
        var out = new ByteArrayOutputStream();
        out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"teste.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes); out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }
    private HttpResponse<byte[]> enviar(String metodo, String caminho, byte[] corpo, UUID id, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(endereco(caminho));
        if (id != null) request.header("Authorization", "Bearer " + token(id));
        if (headers.length > 0) request.headers(headers);
        if (corpo != null) request.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        return HTTP.send(request.method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(corpo)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private String token(UUID id) {
        var claims = SessaoJwtTestFixtures.claims(id, Instant.now());
        claims.put("papel", "artesao"); // Profile rechecks the actual role in PostgreSQL.
        return SessaoJwtTestFixtures.token(claims);
    }
    private URI endereco(String path) { return URI.create("http://localhost:" + port + path); }
    private String url(HttpResponse<byte[]> response) {
        return JSON.readTree(response.body()).get("dados").get("imagem").asText();
    }
    private void assertErro(HttpResponse<byte[]> response, int status) {
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(status);
        var body = JSON.readTree(response.body());
        assertThat(body.get("dados").isNull()).isTrue();
        assertThat(body.get("erro").get("codigo").asText()).isNotBlank();
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .doesNotContain("seaweedfs", "balaio-media", "profile/", "stackTrace", "java.lang.");
    }
    private byte[] imagem(String formato) throws Exception {
        if (formato.equals("webp")) return Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), formato, out);
        return out.toByteArray();
    }
}
