package br.com.balaio.profile.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import br.com.balaio.profile.account.application.port.in.AutorizarOperacaoAdministrativaUseCase;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.application.port.out.ControleAdministracaoPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.ControleAdministracao;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.SenhaProtegida;

class AlterarPapelAdministrativoServiceTest {
    @ParameterizedTest
    @ValueSource(strings={"controle","consulta","contagem","persistencia"})
    void falhasInesperadasNaoRevelamDadosDasContas(String etapa) {
        var contas=mock(ContasPort.class);
        var controle=mock(ControleAdministracaoPort.class);
        var autorizacao=mock(AutorizarOperacaoAdministrativaUseCase.class);
        var service=new AlterarPapelAdministrativoService(contas,controle,autorizacao);
        var alvo=new Conta(UUID.randomUUID(),"Conta de teste",new Email("fixture@example.test"),
                new SenhaProtegida("{bcrypt}$2b$12$"+"A".repeat(53)),Papel.COMPRADOR,false);
        when(controle.bloquear()).thenReturn(new ControleAdministracao(true));
        when(contas.bloquearPorId(alvo.id())).thenReturn(Optional.of(alvo));
        when(contas.contarPorPapel(Papel.ADMIN)).thenReturn(1L);
        when(contas.atualizarPapel(any(),any(),any())).thenReturn(true);
        var erro=new IllegalStateException(alvo.email().valor()+alvo.senhaProtegida().valor());
        switch(etapa) {
            case "controle" -> when(controle.bloquear()).thenThrow(erro);
            case "consulta" -> when(contas.bloquearPorId(alvo.id())).thenThrow(erro);
            case "contagem" -> when(contas.contarPorPapel(Papel.ADMIN)).thenThrow(erro);
            case "persistencia" -> when(contas.atualizarPapel(any(),any(),any())).thenThrow(erro);
            default -> throw new AssertionError();
        }

        Throwable falha=catchThrowable(() -> service.alterar(new IdentidadeAutenticada(UUID.randomUUID()),
                new AlterarPapelComando(alvo.id(),Papel.ADMIN)));

        assertThat(falha).isInstanceOf(AlteracaoPapelException.class).hasNoCause();
        assertThat(((AlteracaoPapelException)falha).motivo()).isEqualTo(AlteracaoPapelException.Motivo.FALHA_ALTERACAO);
        var texto=new StringWriter();
        falha.printStackTrace(new PrintWriter(texto));
        assertThat(texto.toString()).doesNotContain(alvo.email().valor(),alvo.senhaProtegida().valor());
    }
}
