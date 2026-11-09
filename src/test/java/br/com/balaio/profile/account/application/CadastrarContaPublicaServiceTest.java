package br.com.balaio.profile.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import br.com.balaio.profile.account.application.port.out.CodificarSenhaPort;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.EmailJaCadastradoException;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.SenhaProtegida;

class CadastrarContaPublicaServiceTest {
    private static final String SENHA = "  Aa9!${LITERAL}-cadastro  ";
    private static final SenhaProtegida HASH = new SenhaProtegida("{bcrypt}$2b$12$" + "A".repeat(53));
    private final ContasPort contas = mock(ContasPort.class);
    private final CodificarSenhaPort senhas = mock(CodificarSenhaPort.class);
    private final CadastrarContaPublicaService service = new CadastrarContaPublicaService(contas, senhas);

    @Test
    void cadastraCompradorComIdentidadePropriaNomeEmailNormalizadosESenhaExata() {
        prepararCadastroValido();

        ContaPublicaCadastrada resultado = service.cadastrar(
                new CadastrarContaPublicaComando(" \tMaria  Silva\n", "  MARIA+LOJA@EXAMPLE.TEST  ", SENHA));

        var captor = ArgumentCaptor.forClass(Conta.class);
        verify(contas).salvar(captor.capture());
        Conta salva = captor.getValue();
        assertThat(salva.id()).isNotNull();
        assertThat(salva.nome()).isEqualTo("Maria  Silva");
        assertThat(salva.email()).isEqualTo(new Email("maria+loja@example.test"));
        assertThat(salva.papel()).isEqualTo(Papel.COMPRADOR);
        assertThat(salva.trocaSenhaObrigatoria()).isFalse();
        assertThat(salva.senhaProtegida()).isEqualTo(HASH);
        assertThat(resultado.id()).isEqualTo(salva.id());
        assertThat(resultado.papel()).isEqualTo(Papel.COMPRADOR);
        assertThat(resultado.nome()).isEqualTo(salva.nome());
        assertThat(resultado.email()).isEqualTo(salva.email().valor());
        verify(senhas).codificar(SENHA);
        verify(contas).buscarPorEmail(new Email("maria+loja@example.test"));
    }

    @ParameterizedTest
    @CsvSource({ "comprador, COMPRADOR", "artesao, ARTESAO" })
    void preservaSomenteOPapelPublicoSolicitado(String codigo, Papel papel) {
        prepararCadastroValido();

        var resultado = service.cadastrar(new CadastrarContaPublicaComando(
                "Maria Silva", "maria@example.test", SENHA, codigo));

        var captor = ArgumentCaptor.forClass(Conta.class);
        verify(contas).salvar(captor.capture());
        assertThat(captor.getValue().papel()).isEqualTo(papel);
        assertThat(captor.getValue().trocaSenhaObrigatoria()).isFalse();
        assertThat(resultado.papel()).isEqualTo(papel);
    }

    @ParameterizedTest
    @ValueSource(strings = { "admin", "organizador", "ADMIN", "AdMiN", " admin ", "ARTESAO", " artesao",
            "", " ", "ROLE_ADMIN", "comprador,admin", "[\"admin\"]", "\u0430dmin", "\uff41\uff44\uff4d\uff49\uff4e" })
    void rejeitaPapelInvalidoOuPrivilegiadoAntesDeQualquerIo(String papel) {
        assertThatThrownBy(() -> service.cadastrar(new CadastrarContaPublicaComando(
                "Maria Silva", "maria@example.test", SENHA, papel)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();

        verifyNoInteractions(contas, senhas);
    }

    @ParameterizedTest(name = "entrada invalida [{index}]")
    @MethodSource("entradasInvalidas")
    void rejeitaDadosInvalidosAntesDeConsultarOuCodificar(CadastrarContaPublicaComando comando) {
        assertThatThrownBy(() -> service.cadastrar(comando))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();

        verifyNoInteractions(contas, senhas);
    }

    @Test
    void rejeitaEmailExistenteSemCodificarOuAlterarConta() {
        var email = new Email("maria@example.test");
        var existente = new Conta(UUID.randomUUID(), "Conta existente", email, HASH, Papel.ADMIN, true);
        when(contas.buscarPorEmail(email)).thenReturn(Optional.of(existente));

        assertThatThrownBy(() -> service.cadastrar(new CadastrarContaPublicaComando(
                "Outra conta", " MARIA@EXAMPLE.TEST ", SENHA, "artesao")))
                .isInstanceOf(EmailJaCadastradoException.class).hasNoCause();

        verifyNoInteractions(senhas);
    }

    @Test
    void preservaExcecaoDeNegocioDeDuplicidadeNaPersistencia() {
        prepararCadastroValido();
        var duplicidade = new EmailJaCadastradoException();
        when(contas.salvar(any(Conta.class))).thenThrow(duplicidade);

        Throwable falha = catchThrowable(() -> service.cadastrar(comandoValido()));

        assertThat(falha).isSameAs(duplicidade);
    }

    @ParameterizedTest
    @ValueSource(strings = { "consulta", "codificacao", "persistencia" })
    void falhasInesperadasNaoExponhemCredenciaisOuCausas(String etapa) {
        prepararCadastroValido();
        var falhaComSegredo = new IllegalArgumentException(SENHA + " " + HASH.valor());
        switch (etapa) {
            case "consulta" -> when(contas.buscarPorEmail(any(Email.class))).thenThrow(falhaComSegredo);
            case "codificacao" -> when(senhas.codificar(SENHA)).thenThrow(falhaComSegredo);
            case "persistencia" -> when(contas.salvar(any(Conta.class))).thenThrow(falhaComSegredo);
            default -> throw new AssertionError("Etapa de teste desconhecida");
        }

        Throwable falha = catchThrowable(() -> service.cadastrar(comandoValido()));

        assertThat(falha).isInstanceOf(CadastroPublicoException.class).hasNoCause();
        var texto = new StringWriter();
        falha.printStackTrace(new PrintWriter(texto));
        assertThat(texto.toString()).doesNotContain(SENHA, HASH.valor());
    }

    @Test
    void comandoNaoRevelaDadosPessoaisOuSenhaNaRepresentacaoTextual() {
        var comando = comandoValido();

        assertThat(comando.toString()).doesNotContain(comando.nome(), comando.email(), SENHA);
    }

    @Test
    void resultadoNaoRevelaDadosPessoaisSenhaOuHashNaRepresentacaoTextual() {
        prepararCadastroValido();
        var resultado = service.cadastrar(comandoValido());

        assertThat(resultado.toString()).doesNotContain("Maria Silva", "maria@example.test", SENHA, HASH.valor());
    }

    private void prepararCadastroValido() {
        when(contas.buscarPorEmail(any(Email.class))).thenReturn(Optional.empty());
        when(senhas.codificar(SENHA)).thenReturn(HASH);
    }

    private CadastrarContaPublicaComando comandoValido() {
        return new CadastrarContaPublicaComando("Maria Silva", "maria@example.test", SENHA, "comprador");
    }

    private static Stream<Arguments> entradasInvalidas() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(new CadastrarContaPublicaComando(null, "maria@example.test", SENHA)),
                Arguments.of(new CadastrarContaPublicaComando(" \t\n", "maria@example.test", SENHA)),
                Arguments.of(new CadastrarContaPublicaComando("a".repeat(241), "maria@example.test", SENHA)),
                Arguments.of(new CadastrarContaPublicaComando("Maria", null, SENHA)),
                Arguments.of(new CadastrarContaPublicaComando("Maria", "email-invalido", SENHA)),
                Arguments.of(new CadastrarContaPublicaComando("Maria", "maria@example.test", null)),
                Arguments.of(new CadastrarContaPublicaComando("Maria", "maria@example.test", "curta")));
    }
}
