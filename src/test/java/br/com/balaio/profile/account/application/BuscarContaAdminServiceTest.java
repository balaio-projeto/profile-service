package br.com.balaio.profile.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import br.com.balaio.profile.account.application.port.in.AutorizarOperacaoAdministrativaUseCase;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.SenhaProtegida;

class BuscarContaAdminServiceTest {
    private final ContasPort contas = mock(ContasPort.class);
    private final AutorizarOperacaoAdministrativaUseCase autorizacao = mock(AutorizarOperacaoAdministrativaUseCase.class);
    private final BuscarContaAdminService service = new BuscarContaAdminService(contas, autorizacao);
    private final IdentidadeAutenticada admin = new IdentidadeAutenticada(UUID.randomUUID());

    @Test
    void adminEncontraContaPeloEmailNormalizado() {
        Conta conta = conta("alvo@example.test", Papel.COMPRADOR);
        when(contas.buscarPorEmail(new Email("alvo@example.test"))).thenReturn(Optional.of(conta));

        ContaAdministrativa encontrada = service.buscarPorEmail(admin, " ALVO@example.test ");

        assertThat(encontrada).isEqualTo(new ContaAdministrativa(conta.id(), "Conta de teste", "alvo@example.test",
                Papel.COMPRADOR));
        verify(autorizacao).exigirPermissao(admin);
    }

    @Test
    void naoAdministradorRecebeAcessoNegadoSemConsultarContas() {
        doThrow(new AcessoNegadoException(AcessoNegadoException.Motivo.PRIVILEGIO_INSUFICIENTE))
                .when(autorizacao).exigirPermissao(admin);

        Throwable falha = catchThrowable(() -> service.buscarPorEmail(admin, "alvo@example.test"));

        assertThat(falha).isInstanceOf(AcessoNegadoException.class);
        assertThat(((AcessoNegadoException) falha).motivo())
                .isEqualTo(AcessoNegadoException.Motivo.PRIVILEGIO_INSUFICIENTE);
        verify(contas, never()).buscarPorEmail(any());
    }

    @Test
    void solicitanteAusenteNaoEhAutenticado() {
        Throwable falha = catchThrowable(() -> service.buscarPorEmail(null, "alvo@example.test"));

        assertThat(((AcessoNegadoException) falha).motivo()).isEqualTo(AcessoNegadoException.Motivo.NAO_AUTENTICADO);
        verify(autorizacao, never()).exigirPermissao(any());
    }

    @Test
    void emailSemContaRetornaNaoEncontrada() {
        when(contas.buscarPorEmail(any())).thenReturn(Optional.empty());

        Throwable falha = catchThrowable(() -> service.buscarPorEmail(admin, "ninguem@example.test"));

        assertThat(((BuscaContaAdminException) falha).motivo())
                .isEqualTo(BuscaContaAdminException.Motivo.CONTA_NAO_ENCONTRADA);
    }

    @Test
    void emailInvalidoOuAusenteNaoConsultaContas() {
        for (String email : new String[] { null, "", "sem-arroba", "a@b" }) {
            Throwable falha = catchThrowable(() -> service.buscarPorEmail(admin, email));

            assertThat(((BuscaContaAdminException) falha).motivo())
                    .isEqualTo(BuscaContaAdminException.Motivo.EMAIL_INVALIDO);
        }
        verify(contas, never()).buscarPorEmail(any());
    }

    @Test
    void falhaInesperadaNaoRevelaDadosDaConsulta() {
        when(contas.buscarPorEmail(any())).thenThrow(new IllegalStateException("alvo@example.test"));

        Throwable falha = catchThrowable(() -> service.buscarPorEmail(admin, "alvo@example.test"));

        assertThat(falha).isInstanceOf(BuscaContaAdminException.class).hasNoCause()
                .hasMessageNotContaining("alvo@example.test");
        assertThat(((BuscaContaAdminException) falha).motivo())
                .isEqualTo(BuscaContaAdminException.Motivo.FALHA_CONSULTA);
    }

    private static Conta conta(String email, Papel papel) {
        return new Conta(UUID.randomUUID(), "Conta de teste", new Email(email),
                new SenhaProtegida("{bcrypt}$2b$12$" + "A".repeat(53)), papel, false);
    }
}
