package br.com.balaio.profile.artisan.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ColetivosControllerTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void composicaoPublicaExcluiRascunhosInativasEDeOutroArtesao() {
        var dados = json.readTree("""
                [
                  {"slug":"ativa","artesao":"ana","situacao":"publicada","inativadoEm":null},
                  {"slug":"rascunho","artesao":"ana","situacao":"rascunho","inativadoEm":null},
                  {"slug":"inativa","artesao":"ana","situacao":"publicada","inativadoEm":"2026-11-21"},
                  {"slug":"outra","artesao":"bia","situacao":"publicada","inativadoEm":null}
                ]
                """);

        assertThat(ColetivosController.pecasAtivas(dados, "ana"))
                .extracting(peca -> peca.get("slug").asText())
                .containsExactly("ativa");
        assertThat(ColetivosController.pecasAtivas(json.readTree("[]"), "ana")).isEmpty();
    }
}
