package br.com.balaio.profile.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import br.com.balaio.profile.account.application.ContaPublicaCadastrada;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.account.application.port.in.ConsultarUsuarioAtualUseCase;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.security.ValidarSessaoAdapter;

class ArtesaoAtualControllerTest {
    @Test
    void devolveSlugEstavelDaContaAutenticadaESomenteParaArtesao() {
        var sessoes = mock(ValidarSessaoAdapter.class);
        var usuarios = mock(ConsultarUsuarioAtualUseCase.class);
        var request = mock(HttpServletRequest.class);
        var id = UUID.randomUUID();
        var identidade = new IdentidadeAutenticada(id);
        when(request.getHeaders(HttpHeaders.AUTHORIZATION))
                .thenAnswer(invocation -> java.util.Collections.enumeration(List.of("Bearer teste")));
        when(sessoes.validarBearer(List.of("Bearer teste"))).thenReturn(identidade);
        when(usuarios.consultar(identidade))
                .thenReturn(new ContaPublicaCadastrada(id, "Ana", "ana@exemplo.test", Papel.ARTESAO));
        var controller = new ArtesaoAtualController(sessoes, usuarios);
        var resposta = controller.consultar(request);
        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody().dados().slug()).isEqualTo("artesao-" + id);

        when(usuarios.consultar(identidade))
                .thenReturn(new ContaPublicaCadastrada(id, "Ana", "ana@exemplo.test", Papel.COMPRADOR));
        var proibida = controller.consultar(request);
        assertThat(proibida.getStatusCode().value()).isEqualTo(403);
        assertThat(proibida.getBody().erro().codigo()).isEqualTo("SEM_PERMISSAO");
    }
}
