package br.com.balaio.profile.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.application.port.out.VerificarSenhaPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.SenhaProtegida;

class EfetuarLoginServiceTest {
    private static final String SENHA = "  literal ${SEGREDO} de fixture  ";
    private static final SenhaProtegida HASH = new SenhaProtegida("{bcrypt}$2b$12$" + "A".repeat(53));
    private final ContasPort contas = mock(ContasPort.class);
    private final VerificarSenhaPort senhas = mock(VerificarSenhaPort.class);
    private final EfetuarLoginService service = new EfetuarLoginService(contas, senhas);

    @ParameterizedTest
    @EnumSource(Papel.class)
    void normalizaEmailPreservaSenhaLiteralERetornaIdentidadeAtualDoBanco(Papel papel) {
        Conta conta = new Conta(UUID.randomUUID(), "Maria Silva", new Email("maria@example.test"), HASH, papel, true);
        when(contas.buscarPorEmail(conta.email())).thenReturn(Optional.of(conta));
        when(senhas.corresponde(SENHA, HASH)).thenReturn(true);

        var resultado = service.efetuar(new EfetuarLoginComando("  MARIA@EXAMPLE.TEST  ", SENHA));

        assertThat(resultado.id()).isEqualTo(conta.id());
        assertThat(resultado.nome()).isEqualTo(conta.nome());
        assertThat(resultado.email()).isEqualTo(conta.email().valor());
        assertThat(resultado.papel()).isEqualTo(papel);
        verify(senhas).corresponde(SENHA, HASH);
        assertThat(resultado.toString()).doesNotContain(conta.nome(), conta.email().valor(), SENHA, HASH.valor());
    }

    @Test
    void loginNaoReaplicaPoliticaDe12CaracteresACredencialExistente() {
        Conta conta = new Conta(UUID.randomUUID(), "Maria", new Email("maria@example.test"), HASH, Papel.COMPRADOR, false);
        when(contas.buscarPorEmail(conta.email())).thenReturn(Optional.of(conta));
        when(senhas.corresponde("curta", HASH)).thenReturn(true);

        assertThat(service.efetuar(new EfetuarLoginComando(conta.email().valor(), "curta")).id()).isEqualTo(conta.id());
        verify(senhas).corresponde("curta", HASH);
    }

    @Test
    void contaAusenteExecutaComparacaoDummyERejeitaSemIdentidadePublica() {
        when(contas.buscarPorEmail(new Email("ausente@example.test"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.efetuar(new EfetuarLoginComando("ausente@example.test", SENHA)))
                .isInstanceOf(CredenciaisInvalidasException.class).hasNoCause();
        verify(senhas).verificarContaAusente(SENHA);
    }

    @Test
    void senhaIncorretaNaoRetornaConta() {
        Conta conta = new Conta(UUID.randomUUID(), "Maria", new Email("maria@example.test"), HASH, Papel.ADMIN, false);
        when(contas.buscarPorEmail(conta.email())).thenReturn(Optional.of(conta));
        when(senhas.corresponde(SENHA, HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.efetuar(new EfetuarLoginComando(conta.email().valor(), SENHA)))
                .isInstanceOf(CredenciaisInvalidasException.class).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = { "consulta", "comparacao", "dummy" })
    void falhaInesperadaNaoExpoeCredenciaisOuCausa(String etapa) {
        var email = new Email("maria@example.test");
        var causa = new IllegalStateException(SENHA + HASH.valor());
        if (etapa.equals("consulta")) {
            when(contas.buscarPorEmail(email)).thenThrow(causa);
        } else if (etapa.equals("comparacao")) {
            when(contas.buscarPorEmail(email)).thenReturn(Optional.of(
                    new Conta(UUID.randomUUID(), "Maria", email, HASH, Papel.COMPRADOR, false)));
            when(senhas.corresponde(SENHA, HASH)).thenThrow(causa);
        } else {
            when(contas.buscarPorEmail(email)).thenReturn(Optional.empty());
            doThrow(causa).when(senhas).verificarContaAusente(SENHA);
        }
        assertThatThrownBy(() -> service.efetuar(new EfetuarLoginComando(email.valor(), SENHA)))
                .isInstanceOf(AutenticacaoException.class).hasNoCause().hasMessageNotContaining(SENHA)
                .hasMessageNotContaining(HASH.valor());
    }

    @ParameterizedTest
    @MethodSource("entradasInvalidas")
    void entradaInvalidaNaoConsultaBancoNemVerificador(EfetuarLoginComando comando) {
        assertThatThrownBy(() -> service.efetuar(comando)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        verifyNoInteractions(contas, senhas);
    }

    @Test
    void comandoNaoExpoeEmailOuSenhaNaRepresentacao() {
        assertThat(new EfetuarLoginComando("maria@example.test", SENHA).toString())
                .doesNotContain("maria@example.test", SENHA);
    }

    private static Stream<Arguments> entradasInvalidas() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(new EfetuarLoginComando(null, SENHA)),
                Arguments.of(new EfetuarLoginComando(" ", SENHA)),
                Arguments.of(new EfetuarLoginComando("email-invalido", SENHA)),
                Arguments.of(new EfetuarLoginComando("maria@example.test", null)),
                Arguments.of(new EfetuarLoginComando("maria@example.test", "")));
    }
}
