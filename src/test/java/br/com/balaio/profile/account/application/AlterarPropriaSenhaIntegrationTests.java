package br.com.balaio.profile.account.application;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.port.in.AlterarPropriaSenhaUseCase;
import br.com.balaio.profile.account.application.port.in.AutorizarOperacaoAdministrativaUseCase;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.application.port.out.VerificarSenhaPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.SenhaProtegida;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgreSQLTestConfiguration.class)
class AlterarPropriaSenhaIntegrationTests {
    private static final String SENHA_ATUAL = "Aa9!" + UUID.randomUUID();
    private static final String NOVA_SENHA = "Bb8!" + UUID.randomUUID();

    @MockitoBean(enforceOverride = true)
    private InicializarAdministradorUseCase inicializarAdministrador;

    @Autowired private AlterarPropriaSenhaUseCase alterarPropriaSenha;
    @Autowired private AutorizarOperacaoAdministrativaUseCase autorizarOperacaoAdministrativa;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private ContasPort contas;
    @MockitoSpyBean private VerificarSenhaPort verificarSenha;

    private ContasPort contasSpy;
    private VerificarSenhaPort verificarSenhaSpy;

    @BeforeEach
    void prepararCenario() {
        contasSpy = AopTestUtils.getUltimateTargetObject(contas);
        verificarSenhaSpy = AopTestUtils.getUltimateTargetObject(verificarSenha);
        reset(contasSpy, verificarSenhaSpy);
        limparBancoDeTeste();
    }

    @AfterEach
    void limparCenario() {
        reset(contasSpy, verificarSenhaSpy);
        limparBancoDeTeste();
    }

    @ParameterizedTest
    @EnumSource(Papel.class)
    void senhaAtualCorretaPermiteAlterarAPropriaSenhaMesmoComObrigatoriedadePendente(Papel papel) {
        Conta original = criarConta(papel, true);

        alterarPropriaSenha.alterar(identidade(original), comandoValido());

        Conta atualizada = recarregar(original);
        assertThat(atualizada.id()).isEqualTo(original.id());
        assertThat(atualizada.nome()).isEqualTo(original.nome());
        assertThat(atualizada.email()).isEqualTo(original.email());
        assertThat(atualizada.papel()).isEqualTo(original.papel());
        assertThat(atualizada.trocaSenhaObrigatoria()).isFalse();
        assertThat(atualizada.senhaProtegida().valor()).matches("\\{bcrypt\\}\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}")
                .isNotEqualTo(original.senhaProtegida().valor()).doesNotContain(NOVA_SENHA, SENHA_ATUAL);
        assertThat(passwordEncoder.matches(NOVA_SENHA, atualizada.senhaProtegida().valor())).isTrue();
        assertThat(passwordEncoder.matches(SENHA_ATUAL, atualizada.senhaProtegida().valor())).isFalse();
        assertThat(jdbc.queryForObject("select senha_hash from contas where id = ?", String.class, original.id()))
                .isEqualTo(atualizada.senhaProtegida().valor());
        assertThat(bootstrapConcluido()).isFalse();
    }

    @Test
    void contaSemObrigatoriedadeTambemPodeAlterarAPropriaSenha() {
        Conta original = criarConta(Papel.COMPRADOR, false);
        alterarPropriaSenha.alterar(identidade(original), comandoValido());
        assertThat(recarregar(original).trocaSenhaObrigatoria()).isFalse();
        assertThat(passwordEncoder.matches(NOVA_SENHA, recarregar(original).senhaProtegida().valor())).isTrue();
    }

    @Test
    void senhaAtualIncorretaNaoAlteraNenhumCampoNemRemoveObrigatoriedade() {
        Conta original = criarConta(Papel.ADMIN, true);
        Throwable falha = catchThrowable(() -> alterarPropriaSenha.alterar(identidade(original),
                new AlterarPropriaSenhaComando("Senha-incorreta-de-fixture", NOVA_SENHA)));
        conferirFalhaDeSenha(falha, AlteracaoSenhaException.Motivo.SENHA_ATUAL_INCORRETA);
        assertThat(recarregar(original)).isEqualTo(original);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void exigeSenhaAtualSemAlterarAConta(String senhaAtual) {
        Conta original = criarConta(Papel.ADMIN, true);
        Throwable falha = catchThrowable(() -> alterarPropriaSenha.alterar(identidade(original),
                new AlterarPropriaSenhaComando(senhaAtual, NOVA_SENHA)));
        conferirFalhaDeSenha(falha, AlteracaoSenhaException.Motivo.SENHA_ATUAL_OBRIGATORIA);
        assertThat(recarregar(original)).isEqualTo(original);
    }

    @Test
    void naoPermiteReutilizarASenhaAtualParaLimparAObrigatoriedade() {
        Conta original = criarConta(Papel.ADMIN, true);
        Throwable falha = catchThrowable(() -> alterarPropriaSenha.alterar(identidade(original),
                new AlterarPropriaSenhaComando(SENHA_ATUAL, SENHA_ATUAL)));
        conferirFalhaDeSenha(falha, AlteracaoSenhaException.Motivo.SENHA_REUTILIZADA);
        assertThat(recarregar(original)).isEqualTo(original);
    }

    @ParameterizedTest(name = "nova senha invalida [{index}]")
    @MethodSource("novasSenhasInvalidas")
    void novaSenhaInvalidaPreservaHashEObrigatoriedade(String novaSenha) {
        Conta original = criarConta(Papel.ADMIN, true);
        Throwable falha = catchThrowable(() -> alterarPropriaSenha.alterar(identidade(original),
                new AlterarPropriaSenhaComando(SENHA_ATUAL, novaSenha)));
        conferirFalhaDeSenha(falha, AlteracaoSenhaException.Motivo.NOVA_SENHA_INVALIDA);
        assertThat(recarregar(original)).isEqualTo(original);
    }

    @Test
    void identidadeAusenteNaoPermiteEscolherContaPeloComando() {
        Conta original = criarConta(Papel.ADMIN, true);
        assertThatThrownBy(() -> alterarPropriaSenha.alterar(null, comandoValido()))
                .isInstanceOf(AcessoNegadoException.class);
        assertThat(recarregar(original)).isEqualTo(original);
    }

    @Test
    void contaDaIdentidadeInexistenteNaoAlteraOutraConta() {
        Conta original = criarConta(Papel.ADMIN, true);
        assertThatThrownBy(() -> alterarPropriaSenha.alterar(new IdentidadeAutenticada(UUID.randomUUID()), comandoValido()))
                .isInstanceOf(AcessoNegadoException.class);
        assertThat(recarregar(original)).isEqualTo(original);
    }

    @Test
    void comandoAusenteNaoAlteraConta() {
        Conta original = criarConta(Papel.ADMIN, true);
        Throwable falha = catchThrowable(() -> alterarPropriaSenha.alterar(identidade(original), null));
        conferirFalhaDeSenha(falha, AlteracaoSenhaException.Motivo.COMANDO_INVALIDO);
        assertThat(recarregar(original)).isEqualTo(original);
    }

    @Test
    void falhaDepoisDaAtualizacaoRealReverteHashEIndicadorSemExporSegredos() {
        Conta original = criarConta(Papel.ADMIN, true);
        doAnswer(invocacao -> {
            assertThat(invocacao.callRealMethod()).isEqualTo(true);
            assertThat(jdbc.queryForObject("select troca_senha_obrigatoria from contas where id=?", Boolean.class, original.id())).isFalse();
            throw new IllegalStateException("Falha sintetica " + SENHA_ATUAL + NOVA_SENHA);
        }).when(contasSpy).atualizarSenha(any(UUID.class), any(SenhaProtegida.class), any(SenhaProtegida.class));

        Throwable falha = catchThrowable(() -> alterarPropriaSenha.alterar(identidade(original), comandoValido()));

        conferirFalhaDeSenha(falha, AlteracaoSenhaException.Motivo.FALHA_ALTERACAO);
        assertThat(falha).hasNoCause();
        StringWriter representacao = new StringWriter();
        falha.printStackTrace(new PrintWriter(representacao));
        assertThat(representacao.toString()).doesNotContain(SENHA_ATUAL, NOVA_SENHA, original.senhaProtegida().valor());
        assertThat(recarregar(original)).isEqualTo(original);
    }

    @Test
    void adminPendenteNaoExecutaEfeitosProtegidosMasPodeAlterarAPropriaSenha() {
        Conta original = criarConta(Papel.ADMIN, true);
        AtomicBoolean executada = new AtomicBoolean();
        Throwable falha = catchThrowable(() -> transacao().executeWithoutResult(status -> {
            autorizarOperacaoAdministrativa.exigirPermissao(identidade(original));
            executada.set(true);
            jdbc.update("update contas set nome=? where id=?", "Efeito administrativo", original.id());
        }));
        assertThat(falha).isInstanceOf(AcessoNegadoException.class);
        assertThat(((AcessoNegadoException) falha).motivo()).isEqualTo(AcessoNegadoException.Motivo.TROCA_SENHA_OBRIGATORIA);
        assertThat(executada).isFalse();
        assertThat(recarregar(original)).isEqualTo(original);

        alterarPropriaSenha.alterar(identidade(original), comandoValido());

        transacao().executeWithoutResult(status -> {
            autorizarOperacaoAdministrativa.exigirPermissao(identidade(original));
            executada.set(true);
            jdbc.update("update contas set nome=? where id=?", "Efeito administrativo", original.id());
        });
        assertThat(executada).isTrue();
        assertThat(recarregar(original).nome()).isEqualTo("Efeito administrativo");
    }

    @ParameterizedTest
    @EnumSource(value = Papel.class, names = { "COMPRADOR", "ARTESAO" })
    void ausenciaDePendenciaNaoConcedePrivilegiosAdministrativos(Papel papel) {
        Conta original = criarConta(papel, false);
        AtomicBoolean executada = new AtomicBoolean();
        Throwable falha = catchThrowable(() -> transacao().executeWithoutResult(status -> {
            autorizarOperacaoAdministrativa.exigirPermissao(identidade(original));
            executada.set(true);
        }));
        assertThat(falha).isInstanceOf(AcessoNegadoException.class);
        assertThat(((AcessoNegadoException) falha).motivo()).isEqualTo(AcessoNegadoException.Motivo.PRIVILEGIO_INSUFICIENTE);
        assertThat(executada).isFalse();
    }

    @Test
    void guardExigeATransacaoDaOperacaoProtegida() {
        Conta original = criarConta(Papel.ADMIN, false);
        assertThatThrownBy(() -> autorizarOperacaoAdministrativa.exigirPermissao(identidade(original)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void guardConsultaEstadoAtualMesmoSeJpaJaTiverSnapshotAnterior() {
        Conta original = criarConta(Papel.ADMIN, false);
        transacao().executeWithoutResult(status -> {
            assertThat(contas.buscarPorId(original.id()).orElseThrow().trocaSenhaObrigatoria()).isFalse();
            jdbc.update("update contas set troca_senha_obrigatoria=true where id=?", original.id());
            Throwable falha = catchThrowable(() -> autorizarOperacaoAdministrativa.exigirPermissao(identidade(original)));
            assertThat(falha).isInstanceOf(AcessoNegadoException.class);
            assertThat(((AcessoNegadoException) falha).motivo()).isEqualTo(AcessoNegadoException.Motivo.TROCA_SENHA_OBRIGATORIA);
            status.setRollbackOnly();
        });
        assertThat(recarregar(original)).isEqualTo(original);
    }

    @Test
    void atualizacaoParcialDaSenhaNaoRestauraOutrosCamposDoSnapshot() {
        Conta original = criarConta(Papel.ADMIN, true);
        doAnswer(invocacao -> {
            jdbc.update("update contas set nome=?,papel=? where id=?", "Nome atualizado", "artesao", original.id());
            return invocacao.callRealMethod();
        }).when(contasSpy).atualizarSenha(any(UUID.class), any(SenhaProtegida.class), any(SenhaProtegida.class));

        alterarPropriaSenha.alterar(identidade(original), comandoValido());

        Conta atualizada = recarregar(original);
        assertThat(atualizada.nome()).isEqualTo("Nome atualizado");
        assertThat(atualizada.papel()).isEqualTo(Papel.ARTESAO);
        assertThat(atualizada.trocaSenhaObrigatoria()).isFalse();
        assertThat(passwordEncoder.matches(NOVA_SENHA, atualizada.senhaProtegida().valor())).isTrue();
    }

    @Test
    void releituraNaMesmaTransacaoRefleteHashEIndicadorAtualizados() {
        Conta original = criarConta(Papel.ADMIN, true);
        transacao().executeWithoutResult(status -> {
            assertThat(contas.buscarPorId(original.id())).contains(original);
            alterarPropriaSenha.alterar(identidade(original), comandoValido());
            Conta atualizada = contas.buscarPorId(original.id()).orElseThrow();
            assertThat(atualizada.trocaSenhaObrigatoria()).isFalse();
            assertThat(passwordEncoder.matches(NOVA_SENHA, atualizada.senhaProtegida().valor())).isTrue();
            assertThat(passwordEncoder.matches(SENHA_ATUAL, atualizada.senhaProtegida().valor())).isFalse();
        });
    }

    @Test
    void duasTrocasConcorrentesComASenhaAntigaNaoConfirmamJuntas() throws Exception {
        Conta original = criarConta(Papel.ADMIN, true);
        CyclicBarrier ambasValidaram = new CyclicBarrier(2);
        Set<Integer> conexoes = ConcurrentHashMap.newKeySet();
        doAnswer(invocacao -> {
            Object correta = invocacao.callRealMethod();
            conexoes.add(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
            ambasValidaram.await(15, TimeUnit.SECONDS);
            return correta;
        }).when(verificarSenhaSpy).corresponde(eq(SENHA_ATUAL), any(SenhaProtegida.class));

        List<Object> resultados;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primeira = executor.submit(() -> tentarAlterar(original));
            var segunda = executor.submit(() -> tentarAlterar(original));
            resultados = List.of(primeira.get(40, TimeUnit.SECONDS), segunda.get(40, TimeUnit.SECONDS));
        }

        assertThat(conexoes).hasSize(2);
        assertThat(resultados.stream().filter(Boolean.TRUE::equals).toList()).hasSize(1);
        var falhas = resultados.stream().filter(AlteracaoSenhaException.class::isInstance).toList();
        assertThat(falhas).hasSize(1);
        assertThat(((AlteracaoSenhaException) falhas.getFirst()).motivo())
                .isEqualTo(AlteracaoSenhaException.Motivo.CONFLITO_ATUALIZACAO);
        assertThat(recarregar(original).trocaSenhaObrigatoria()).isFalse();
        assertThat(passwordEncoder.matches(NOVA_SENHA, recarregar(original).senhaProtegida().valor())).isTrue();
    }

    @Test
    void comandoNaoExpoeSenhasNemPermiteReceberIndicadorOuContaAlvo() {
        assertThat(comandoValido().toString()).doesNotContain(SENHA_ATUAL, NOVA_SENHA);
        assertThat(java.util.Arrays.stream(AlterarPropriaSenhaComando.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("senhaAtual", "novaSenha");
    }

    private Object tentarAlterar(Conta conta) {
        try { alterarPropriaSenha.alterar(identidade(conta), comandoValido()); return Boolean.TRUE; }
        catch (RuntimeException exception) { return exception; }
    }
    private Conta criarConta(Papel papel, boolean trocaObrigatoria) {
        return contas.salvar(new Conta(UUID.randomUUID(), "Conta de teste", new Email(UUID.randomUUID()+"@example.test"),
                new SenhaProtegida(passwordEncoder.encode(SENHA_ATUAL)), papel, trocaObrigatoria));
    }
    private Conta recarregar(Conta conta) { return contas.buscarPorId(conta.id()).orElseThrow(); }
    private IdentidadeAutenticada identidade(Conta conta) { return new IdentidadeAutenticada(conta.id()); }
    private AlterarPropriaSenhaComando comandoValido() { return new AlterarPropriaSenhaComando(SENHA_ATUAL, NOVA_SENHA); }
    private TransactionTemplate transacao() { return new TransactionTemplate(transactionManager); }
    private boolean bootstrapConcluido() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select bootstrap_concluido from controle_administracao where id=1", Boolean.class));
    }
    private void limparBancoDeTeste() {
        jdbc.update("delete from contas");
        jdbc.update("update controle_administracao set bootstrap_concluido=false where id=1");
    }
    private void conferirFalhaDeSenha(Throwable falha, AlteracaoSenhaException.Motivo motivo) {
        assertThat(falha).isInstanceOf(AlteracaoSenhaException.class);
        assertThat(((AlteracaoSenhaException) falha).motivo()).isEqualTo(motivo);
    }
    private static Stream<Arguments> novasSenhasInvalidas() {
        return Stream.of(Arguments.of((Object)null), Arguments.of("curta"), Arguments.of(" ".repeat(12)),
                Arguments.of("a".repeat(73)), Arguments.of("é".repeat(37)),
                Arguments.of("\uD83D\uDD12".repeat(11)), Arguments.of("Nova-senha-com\ncontrole"),
                Arguments.of("a".repeat(12)+"\uD800"));
    }
}
