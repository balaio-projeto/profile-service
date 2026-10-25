package br.com.balaio.profile.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.balaio.profile.account.application.port.out.CodificarSenhaPort;
import br.com.balaio.profile.account.application.port.out.ConfiguracaoAdministradorInicialPort;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.application.port.out.ControleAdministracaoPort;
import br.com.balaio.profile.account.domain.ControleAdministracao;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.Papel;

class InicializarAdministradorServiceTest {
    private final ContasPort contas = mock(ContasPort.class);
    private final ControleAdministracaoPort controle = mock(ControleAdministracaoPort.class);
    private final ConfiguracaoAdministradorInicialPort configuracao = mock(ConfiguracaoAdministradorInicialPort.class);
    private final CodificarSenhaPort senhas = mock(CodificarSenhaPort.class);
    private final InicializarAdministradorService service = new InicializarAdministradorService(
            contas, controle, configuracao, senhas);

    @Test
    void conclusaoPersistidaEvitaLerCredenciaisOuAlterarContas() {
        when(controle.bloquear()).thenReturn(new ControleAdministracao(true));

        service.inicializar();

        verifyNoInteractions(configuracao, contas, senhas);
    }

    @Test
    void configuracaoNaoExpoeCredenciaisNaRepresentacaoTextual() {
        String segredo = UUID.randomUUID().toString();
        var parametros = new ConfiguracaoAdministradorInicial("fixture@example.test", segredo);

        assertThat(parametros.toString()).doesNotContain(segredo, parametros.email());
    }

    @Test
    void falhaDeLeituraDoAmbienteNaoExpoeCredenciaisNemCausa() {
        String segredo = UUID.randomUUID().toString();
        when(controle.bloquear()).thenReturn(new ControleAdministracao(false));
        when(configuracao.obter()).thenThrow(new IllegalArgumentException(segredo));

        Throwable falha = catchThrowable(service::inicializar);

        conferirFalhaSanitizada(falha, segredo);
        verifyNoInteractions(contas, senhas);
    }

    @Test
    void falhaDeCodificacaoNaoExpoeCredenciaisNemCausa() {
        String segredo = UUID.randomUUID().toString();
        var email = new Email("fixture@example.test");
        when(controle.bloquear()).thenReturn(new ControleAdministracao(false));
        when(configuracao.obter()).thenReturn(new ConfiguracaoAdministradorInicial(email.valor(), segredo));
        when(contas.buscarPorEmail(email)).thenReturn(Optional.empty());
        when(contas.contarPorPapel(Papel.ADMIN)).thenReturn(0L);
        when(senhas.codificar(segredo)).thenThrow(new IllegalArgumentException(segredo));

        Throwable falha = catchThrowable(service::inicializar);

        conferirFalhaSanitizada(falha, segredo);
        verify(senhas).codificar(segredo);
    }

    private void conferirFalhaSanitizada(Throwable falha, String segredo) {
        assertThat(falha).isInstanceOf(BootstrapAdministradorException.class).hasNoCause();
        var texto = new StringWriter();
        falha.printStackTrace(new PrintWriter(texto));
        assertThat(texto.toString()).doesNotContain(segredo);
    }
}
