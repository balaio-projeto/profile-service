package br.com.balaio.profile.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RecoveryControllerTest {
    private static final String NOVA_SENHA = "NovaSenhaTeste123!";
    private final RecoveryService service = mock(RecoveryService.class);
    private final RecoveryController controller = new RecoveryController(service);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n", "\u2003"})
    void tokenAusenteOuEmBrancoRetornaErroSemChamarServico(String token) {
        var resposta = controller.reset(new RecoveryController.Reset(token, NOVA_SENHA));

        assertTokenInvalido(resposta);
        verifyNoInteractions(service);
    }

    @Test
    void tokenAcimaDe128CaracteresRetornaErroSemChamarServico() {
        var resposta = controller.reset(new RecoveryController.Reset("a".repeat(129), NOVA_SENHA));

        assertTokenInvalido(resposta);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 127, 128})
    void tokenDentroDoLimiteEncaminhaOperacaoUmaVez(int tamanho) {
        String token = "a".repeat(tamanho);
        when(service.reset(token, NOVA_SENHA)).thenReturn(true);

        var resposta = controller.reset(new RecoveryController.Reset(token, NOVA_SENHA));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody()).isEqualTo(ApiResponse.ok(new RecoveryController.ResetReceipt(true)));
        verify(service).reset(token, NOVA_SENHA);
        verifyNoMoreInteractions(service);
    }

    @Test
    void tokenRecusadoPeloServicoMantemMesmoErro() {
        String token = "token-desconhecido";
        when(service.reset(token, NOVA_SENHA)).thenReturn(false);

        var resposta = controller.reset(new RecoveryController.Reset(token, NOVA_SENHA));

        assertTokenInvalido(resposta);
        verify(service).reset(token, NOVA_SENHA);
        verifyNoMoreInteractions(service);
    }

    @Test
    void preservaTokenRecebidoSemNormalizarSeuConteudo() {
        String token = " token com espacos ";
        when(service.reset(token, NOVA_SENHA)).thenReturn(false);

        assertTokenInvalido(controller.reset(new RecoveryController.Reset(token, NOVA_SENHA)));

        verify(service).reset(token, NOVA_SENHA);
        verifyNoMoreInteractions(service);
    }

    @Test
    void falhaDeValidacaoDaSenhaContinuaSendoPropagada() {
        String token = "token-para-redefinicao";
        var falha = new RecoveryPasswordException();
        when(service.reset(token, null)).thenThrow(falha);

        assertThatThrownBy(() -> controller.reset(new RecoveryController.Reset(token, null)))
                .isSameAs(falha);

        verify(service).reset(token, null);
        verifyNoMoreInteractions(service);
    }

    private void assertTokenInvalido(ResponseEntity<ApiResponse<RecoveryController.ResetReceipt>> resposta) {
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resposta.getBody()).isEqualTo(ApiResponse.error(
                "TOKEN_INVALIDO", "Link inválido ou expirado. Solicite outro link."));
    }
}
