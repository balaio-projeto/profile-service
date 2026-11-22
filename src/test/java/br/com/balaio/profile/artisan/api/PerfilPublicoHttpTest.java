package br.com.balaio.profile.artisan.api;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Contexto isolado (propriedade própria) para controlar o estado de perfis sem afetar outras classes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "balaio.teste.contexto=perfil-publico")
@Import(PostgreSQLTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PerfilPublicoHttpTest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String HASH = "{bcrypt}" + new BCryptPasswordEncoder(12).encode("SenhaDeTeste123!");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(enforceOverride = true) InicializarAdministradorUseCase admin;

    @BeforeEach void limparBanco() {
        // Cascata remove perfis_artesao e configurações associadas.
        jdbc.update("DELETE FROM contas");
    }

    @Test void artesaoInexistenteRetorna404ComErroDeRecursoNaoEncontrado() throws Exception {
        var resposta = get("/artesaos/artesao-inexistente");
        assertThat(resposta.statusCode()).isEqualTo(404);
        assertThat(resposta.body()).contains("RECURSO_NAO_ENCONTRADO");
    }

    @Test void listaPublicaVaziaQuandoNaoHaPerfis() throws Exception {
        var resposta = get("/artesaos");
        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(resposta.body()).contains("\"dados\":[]");
    }

    @Test void perfilPublicoEListagemNaoExpoemDadosPrivados() throws Exception {
        UUID conta = UUID.randomUUID();
        String email = "privado-" + conta + "@example.test";
        String slug = "artesao-" + conta;
        jdbc.update("INSERT INTO contas(id,nome,email,senha_hash,papel) VALUES (?,?,?,?,?)",
                conta, "Conta privada", email, HASH, "artesao");
        jdbc.update("INSERT INTO perfis_artesao(id,conta_id,slug,nome,atelie,historia,territorio,tecnica) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), conta, slug, "Nome público", "Ateliê", "História", "Pajeú", "Xilogravura");

        var perfil = get("/artesaos/" + slug);
        assertThat(perfil.statusCode()).isEqualTo(200);
        assertThat(perfil.body()).contains("Nome público", slug)
                .doesNotContain(email, "senha_hash", HASH, "chavePix", "cepOrigem");

        var lista = get("/artesaos");
        assertThat(lista.statusCode()).isEqualTo(200);
        assertThat(lista.body()).contains(slug).doesNotContain(email, "senha_hash", HASH);
    }

    private HttpResponse<String> get(String caminho) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + caminho)).GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
