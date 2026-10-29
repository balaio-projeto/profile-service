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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import br.com.balaio.profile.account.application.port.out.CodificarSenhaPort;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.application.port.out.VerificarSenhaPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.SenhaProtegida;

class AlterarPropriaSenhaServiceTest {
    @Test
    void comandoOcultaAmbasAsSenhas() {
        String atual=UUID.randomUUID().toString();
        String nova=UUID.randomUUID().toString();

        assertThat(new AlterarPropriaSenhaComando(atual,nova).toString()).doesNotContain(atual,nova);
    }

    @Test
    void identidadeExigeIdentificadorValido() {
        assertThat(catchThrowable(() -> new IdentidadeAutenticada(null)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings={"verificacao","codificacao","persistencia"})
    void falhasDasPortasNaoExponhemCredenciaisOuHash(String etapa) {
        var contas=mock(ContasPort.class);
        var codificar=mock(CodificarSenhaPort.class);
        var verificar=mock(VerificarSenhaPort.class);
        var service=new AlterarPropriaSenhaService(contas,codificar,verificar);
        String atual=UUID.randomUUID().toString();
        String nova=UUID.randomUUID().toString();
        var hash=new SenhaProtegida("{bcrypt}$2b$12$"+"A".repeat(53));
        var novoHash=new SenhaProtegida("{bcrypt}$2b$12$"+"B".repeat(53));
        var conta=new Conta(UUID.randomUUID(),"Conta",new Email("fixture@example.test"),hash,Papel.ADMIN,true);
        when(contas.buscarPorId(conta.id())).thenReturn(Optional.of(conta));
        when(verificar.corresponde(atual,hash)).thenReturn(true);
        when(verificar.corresponde(nova,hash)).thenReturn(false);
        when(codificar.codificar(nova)).thenReturn(novoHash);
        var erro=new IllegalStateException(atual+nova+hash.valor());
        switch(etapa) {
            case "verificacao" -> when(verificar.corresponde(atual,hash)).thenThrow(erro);
            case "codificacao" -> when(codificar.codificar(nova)).thenThrow(erro);
            case "persistencia" -> when(contas.atualizarSenha(any(),any(),any())).thenThrow(erro);
            default -> throw new AssertionError();
        }

        Throwable falha=catchThrowable(() -> service.alterar(new IdentidadeAutenticada(conta.id()),
                new AlterarPropriaSenhaComando(atual,nova)));

        assertThat(falha).isInstanceOf(AlteracaoSenhaException.class).hasNoCause();
        var texto=new StringWriter();
        falha.printStackTrace(new PrintWriter(texto));
        assertThat(texto.toString()).doesNotContain(atual,nova,hash.valor());
    }
}
