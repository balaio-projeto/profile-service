package br.com.balaio.profile.account.imagem;

import br.com.balaio.profile.AutenticacaoTestFixtures;
import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.SessaoJwtTestFixtures;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.artisan.media.ArmazenamentoMidiaPort;
import br.com.balaio.profile.artisan.media.MidiaException;
import br.com.balaio.profile.artisan.media.MidiaPerfil;
import br.com.balaio.profile.artisan.media.MidiaPerfilService;
import br.com.balaio.profile.artisan.media.ValidarImagem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "balaio.media.cleanup-delay=3600000")
@Import(PostgreSQLTestConfiguration.class)
class ImagemContaHttpTest {
    private static final String UPLOAD = "/api/v1/usuario/imagem";
    private static final String BOUNDARY = "balaio-conta-boundary";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MidiaPerfilService midias;
    @MockitoBean(enforceOverride = true) InicializarAdministradorUseCase admin;
    @MockitoBean(enforceOverride = true) ArmazenamentoMidiaPort storage;
    final Map<String, byte[]> objetos = new ConcurrentHashMap<>();
    final List<UUID> contas = new ArrayList<>();

    @BeforeEach void preparar() {
        doAnswer(call -> {
            MidiaPerfil m = call.getArgument(0);
            objetos.put(m.chave(), ((byte[]) call.getArgument(1)).clone());
            return null;
        }).when(storage).enviar(any(), any());
        when(storage.recuperar(any())).thenAnswer(call -> objetos.get(((MidiaPerfil) call.getArgument(0)).chave()));
        doAnswer(call -> { objetos.remove(((MidiaPerfil) call.getArgument(0)).chave()); return null; })
                .when(storage).excluir(any());
    }

    @AfterEach void limpar() {
        for (UUID conta : contas) {
            jdbc.update("UPDATE contas SET imagem=NULL WHERE id=?", conta);
            jdbc.update("DELETE FROM midias_perfil WHERE conta_id=? OR perfil_id IN "
                    + "(SELECT id FROM perfis_artesao WHERE conta_id=?)", conta, conta);
            jdbc.update("DELETE FROM contas WHERE id=?", conta);
        }
        contas.clear();
        objetos.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"comprador", "admin", "organizador"})
    void uploadDaContaFicaDisponivelNoUsuarioAtual(String papel) throws Exception {
        UUID conta = criar(papel);
        byte[] bytes = imagem("png");
        var resposta = upload(bytes, conta);
        assertThat(resposta.statusCode()).as(texto(resposta)).isEqualTo(200);
        var dados = JSON.readTree(resposta.body()).get("dados");
        assertThat(dados.get("id").asText()).isEqualTo(conta.toString());
        assertThat(dados.get("papel").asText()).isEqualTo(papel);
        String url = dados.get("imagem").asText();
        assertThat(url).startsWith(MidiaPerfil.PREFIXO_PUBLICO);
        assertThat(texto(resposta)).doesNotContain("account/", "chave", "balaio-media");
        assertThat(imagem(enviar("GET", "/api/v1/usuario", null, conta))).isEqualTo(url);
        var leitura = enviar("GET", url, null, null);
        assertThat(leitura.statusCode()).isEqualTo(200);
        assertThat(leitura.body()).isEqualTo(bytes);
        assertThat(leitura.headers().firstValue("Content-Type")).hasValue("image/png");
    }

    @Test void substituicaoERemocaoDesvinculamImagemAnterior() throws Exception {
        UUID conta = criar("comprador");
        String antiga = imagem(upload(imagem("png"), conta));
        String atual = imagem(upload(imagem("jpeg"), conta));
        assertThat(atual).isNotEqualTo(antiga);
        assertThat(enviar("GET", antiga, null, null).statusCode()).isEqualTo(404);
        var removida = enviar("DELETE", UPLOAD, null, conta);
        assertThat(removida.statusCode()).isEqualTo(200);
        assertThat(imagem(removida)).isEmpty();
        assertThat(imagem(enviar("GET", "/api/v1/usuario", null, conta))).isEmpty();
        assertThat(enviar("GET", atual, null, null).statusCode()).isEqualTo(404);
        midias.limpar();
        assertThat(objetos).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM midias_perfil WHERE conta_id=?", Integer.class, conta)).isZero();
    }

    @Test void edicaoDosDadosPreservaImagem() throws Exception {
        UUID conta = criar("comprador");
        String url = imagem(upload(imagem("png"), conta));
        var request = HttpRequest.newBuilder(endereco("/api/v1/usuario"))
                .header("Authorization", "Bearer " + token(conta)).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"nome\":\"Nome Atualizado\"}")).build();
        var resposta = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(resposta.statusCode()).as(texto(resposta)).isEqualTo(200);
        assertThat(imagem(resposta)).isEqualTo(url);
    }

    @Test void formatoNaoSuportadoRecebe415() throws Exception {
        UUID conta = criar("comprador");
        assertErro(upload("conteudo sem imagem".getBytes(StandardCharsets.UTF_8), conta), 415);
        var gif = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "gif", gif);
        assertErro(upload(gif.toByteArray(), conta), 415);
        var json = HttpRequest.newBuilder(endereco(UPLOAD)).header("Authorization", "Bearer " + token(conta))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertErro(HTTP.send(json, HttpResponse.BodyHandlers.ofByteArray()), 415);
        assertThat(imagem(enviar("GET", "/api/v1/usuario", null, conta))).isEmpty();
        verifyNoInteractions(storage);
    }

    @Test void tamanhoExcedidoRecebe413AntesDoStorage() throws Exception {
        UUID conta = criar("comprador");
        assertErro(upload(new byte[ValidarImagem.MAX_BYTES + 1], conta), 413);
        assertErro(upload(new byte[3 * 1024 * 1024 + 1], conta), 413);
        verifyNoInteractions(storage);
    }

    @Test void arquivoVazioOuAusenteRecebe400() throws Exception {
        UUID conta = criar("comprador");
        assertErro(upload(new byte[0], conta), 400);
        assertErro(enviar("PUT", UPLOAD, ("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII), conta), 400);
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void exigeSessao(String metodo) throws Exception {
        byte[] corpo = metodo.equals("PUT") ? multipart(imagem("png")) : null;
        assertErro(enviar(metodo, UPLOAD, corpo, null), 401);
        assertErro(enviar(metodo, UPLOAD, corpo, null, "Authorization", "Bearer invalido"), 401);
        verifyNoInteractions(storage);
    }

    @Test void cookieExigeOriginPermitido() throws Exception {
        UUID conta = criar("comprador");
        String cookie = "balaio_sessao=" + token(conta);
        assertThat(enviar("PUT", UPLOAD, multipart(imagem("png")), null, "Cookie", cookie).statusCode()).isEqualTo(403);
        assertThat(enviar("PUT", UPLOAD, multipart(imagem("png")), null, "Cookie", cookie,
                "Origin", AutenticacaoTestFixtures.ORIGIN).statusCode()).isEqualTo(200);
    }

    @Test void storageIndisponivelPreservaImagemAnterior() throws Exception {
        UUID conta = criar("comprador");
        String anterior = imagem(upload(imagem("png"), conta));
        doThrow(new MidiaException(MidiaException.Motivo.STORAGE_INDISPONIVEL)).when(storage).enviar(any(), any());
        assertErro(upload(imagem("jpeg"), conta), 503);
        assertThat(imagem(enviar("GET", "/api/v1/usuario", null, conta))).isEqualTo(anterior);
    }

    @Test void artesaoUsaAMesmaFotoDoAtelie() throws Exception {
        UUID conta = criar("artesao");
        String url = imagem(upload(imagem("png"), conta));
        assertThat(url).startsWith(MidiaPerfil.PREFIXO_PUBLICO);
        assertThat(jdbc.queryForObject("SELECT imagem FROM perfis_artesao WHERE conta_id=?", String.class, conta))
                .isEqualTo(url);
        assertThat(jdbc.queryForObject("SELECT imagem FROM contas WHERE id=?", String.class, conta)).isNull();
        assertThat(imagem(enviar("GET", "/api/v1/artesao/me", null, conta))).isEqualTo(url);
        assertThat(imagem(enviar("GET", "/api/v1/usuario", null, conta))).isEqualTo(url);
        assertThat(imagem(enviar("DELETE", UPLOAD, null, conta))).isEmpty();
        assertThat(imagem(enviar("GET", "/api/v1/artesao/me", null, conta))).isEqualTo(MidiaPerfil.PLACEHOLDER);
    }

    @Test void artesaoSemFotoPropriaRecebeAFotoDoAtelie() throws Exception {
        UUID conta = criar("artesao");
        var resposta = enviar("PUT", "/api/v1/artesao/me/imagem", multipart(imagem("png")), conta);
        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(imagem(enviar("GET", "/api/v1/usuario", null, conta))).isEqualTo(imagem(resposta));
    }

    @Test void fotoDaContaAnteriorAoPapelDeArtesaoCedeLugarAoAtelie() throws Exception {
        UUID conta = criar("comprador");
        String daConta = imagem(upload(imagem("png"), conta));
        jdbc.update("UPDATE contas SET papel='artesao' WHERE id=?", conta);
        assertThat(imagem(enviar("GET", "/api/v1/usuario", null, conta))).isEqualTo(daConta);
        String doAtelie = imagem(upload(imagem("jpeg"), conta));
        assertThat(doAtelie).isNotEqualTo(daConta);
        assertThat(jdbc.queryForObject("SELECT imagem FROM contas WHERE id=?", String.class, conta)).isNull();
        assertThat(enviar("GET", daConta, null, null).statusCode()).isEqualTo(404);
        assertThat(imagem(enviar("GET", "/api/v1/usuario", null, conta))).isEqualTo(doAtelie);
    }

    private UUID criar(String papel) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO contas(id,nome,email,senha_hash,papel) VALUES (?,?,?,?,?)",
                id, "Conta Foto", id + "@test.invalid", "{bcrypt}$2b$12$" + ".".repeat(53), papel);
        contas.add(id);
        return id;
    }

    private HttpResponse<byte[]> upload(byte[] bytes, UUID conta) throws Exception {
        return enviar("PUT", UPLOAD, multipart(bytes), conta);
    }

    private byte[] multipart(byte[] bytes) throws Exception {
        var out = new ByteArrayOutputStream();
        out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"foto.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private HttpResponse<byte[]> enviar(String metodo, String caminho, byte[] corpo, UUID conta, String... headers)
            throws Exception {
        var request = HttpRequest.newBuilder(endereco(caminho));
        if (conta != null) request.header("Authorization", "Bearer " + token(conta));
        if (headers.length > 0) request.headers(headers);
        if (corpo != null) request.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        return HTTP.send(request.method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(corpo)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String token(UUID conta) {
        var papel = jdbc.queryForObject("SELECT papel FROM contas WHERE id=?", String.class, conta);
        var claims = SessaoJwtTestFixtures.claims(conta, Instant.now());
        claims.put("papel", papel);
        return SessaoJwtTestFixtures.token(claims);
    }

    private URI endereco(String caminho) {
        return URI.create("http://localhost:" + port + caminho);
    }

    private static String texto(HttpResponse<byte[]> resposta) {
        return new String(resposta.body(), StandardCharsets.UTF_8);
    }

    private static String imagem(HttpResponse<byte[]> resposta) {
        return JSON.readTree(resposta.body()).get("dados").get("imagem").asText();
    }

    private static void assertErro(HttpResponse<byte[]> resposta, int status) {
        assertThat(resposta.statusCode()).as(texto(resposta)).isEqualTo(status);
        var corpo = JSON.readTree(resposta.body());
        assertThat(corpo.get("dados").isNull()).isTrue();
        assertThat(corpo.get("erro").get("codigo").asText()).isNotBlank();
        assertThat(texto(resposta)).doesNotContain("account/", "balaio-media", "stackTrace", "java.lang.");
    }

    private static byte[] imagem(String formato) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), formato, out);
        return out.toByteArray();
    }
}
