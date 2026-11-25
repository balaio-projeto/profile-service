package br.com.balaio.profile.account.imagem;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "balaio.internal-token=token-interno-de-teste-1234567890ab")
@Import(PostgreSQLTestConfiguration.class)
class ImagensContasInternoHttpTest {
    private static final String TOKEN = "token-interno-de-teste-1234567890ab";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HASH = "{bcrypt}"
            + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("SenhaDeTeste123!");

    @LocalServerPort
    private int porta;
    @MockitoBean(enforceOverride = true)
    private InicializarAdministradorUseCase inicializarAdministrador;
    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private UUID comFoto;
    private UUID semFoto;

    @BeforeEach
    void preparar() {
        comFoto = conta("/api/v1/arquivos/perfis/" + UUID.randomUUID());
        semFoto = conta(null);
    }

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM contas WHERE id IN (?,?)", comFoto, semFoto);
    }

    @Test
    void devolveSoAsContasComFotoEExigeTokenInterno() throws Exception {
        var corpo = Map.of("contas", List.of(comFoto.toString(), semFoto.toString(), UUID.randomUUID().toString()));
        assertThat(consultar(corpo, null).statusCode()).isEqualTo(403);

        var resposta = consultar(corpo, TOKEN);
        assertThat(resposta.statusCode()).isEqualTo(200);
        var dados = JSON.readTree(resposta.body()).path("dados");
        assertThat(dados.path(comFoto.toString()).asString()).startsWith("/api/v1/arquivos/perfis/");
        assertThat(dados.has(semFoto.toString())).isFalse();
        assertThat(dados.size()).isEqualTo(1);
    }

    @Test
    void recusaConsultaAcimaDoLimite() throws Exception {
        var muitas = java.util.stream.IntStream.range(0, ImagensContasInternoController.LIMITE_POR_CONSULTA + 1)
                .mapToObj(i -> UUID.randomUUID().toString()).toList();
        assertThat(consultar(Map.of("contas", muitas), TOKEN).statusCode()).isEqualTo(400);
    }

    private UUID conta(String imagem) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO contas(id,nome,email,senha_hash,papel,imagem) VALUES (?,?,?,?,?,?)",
                id, "Conta teste", id + "@example.test", HASH, "comprador", imagem);
        return id;
    }

    private HttpResponse<String> consultar(Object corpo, String token) throws Exception {
        var requisicao = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/internal/v1/contas/imagens"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(corpo)));
        if (token != null) requisicao.header("X-Balaio-Service-Token", token);
        return cliente.send(requisicao.build(), HttpResponse.BodyHandlers.ofString());
    }
}
