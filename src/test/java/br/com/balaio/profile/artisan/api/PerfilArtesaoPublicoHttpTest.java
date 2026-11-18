package br.com.balaio.profile.artisan.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import br.com.balaio.profile.account.security.ValidarSessaoAdapter;
import br.com.balaio.profile.artisan.application.PerfilArtesaoService;
import br.com.balaio.profile.artisan.application.PerfilNaoEncontradoException;
import br.com.balaio.profile.artisan.domain.PerfilArtesao;

class PerfilArtesaoPublicoHttpTest {
    private PerfilArtesaoService perfis;
    private MockMvc http;

    @BeforeEach
    void preparar() {
        perfis = mock(PerfilArtesaoService.class);
        http = MockMvcBuilders.standaloneSetup(new PerfilArtesaoController(
                perfis, mock(ValidarSessaoAdapter.class)))
                .setControllerAdvice(new PerfilArtesaoExceptionHandler())
                .build();
    }

    @Test
    void perfilPublicoMostraDadosDoAtelieSemIdDaConta() throws Exception {
        var perfil = new PerfilArtesao(UUID.randomUUID(), UUID.randomUUID(),
                "maria-do-barro-a1b2c3d4", "Maria", "Ateliê Maria", "História",
                "Caruaru", "Cerâmica & Barro", "/fotos/maria.webp");
        when(perfis.consultar(perfil.slug())).thenReturn(perfil);

        http.perform(get("/api/v1/artesaos/{slug}", perfil.slug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.slug").value(perfil.slug()))
                .andExpect(jsonPath("$.dados.atelie").value("Ateliê Maria"))
                .andExpect(jsonPath("$.dados.contaId").doesNotExist())
                .andExpect(jsonPath("$.dados.email").doesNotExist());
    }

    @Test
    void listaPublicaVaziaRetornaArrayVazio() throws Exception {
        when(perfis.listar()).thenReturn(List.of());

        http.perform(get("/api/v1/artesaos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados").isEmpty());
    }

    @Test
    void perfilInexistenteRetorna404() throws Exception {
        when(perfis.consultar("nao-existe")).thenThrow(new PerfilNaoEncontradoException());

        http.perform(get("/api/v1/artesaos/nao-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro.codigo").value("RECURSO_NAO_ENCONTRADO"));
    }
}
