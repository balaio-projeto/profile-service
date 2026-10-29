package br.com.balaio.profile.account.application;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.port.in.AlterarPapelAdministrativoUseCase;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.application.port.out.ControleAdministracaoPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.SenhaProtegida;
import br.com.balaio.profile.account.domain.UltimoAdministradorException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgreSQLTestConfiguration.class)
class AlterarPapelAdministrativoIntegrationTests {
    private static final SenhaProtegida HASH = new SenhaProtegida("{bcrypt}$2b$12$" + "A".repeat(53));

    @MockitoBean(enforceOverride = true)
    private InicializarAdministradorUseCase inicializarAdministrador;

    @Autowired private AlterarPapelAdministrativoUseCase alterarPapel;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private ContasPort contas;
    @MockitoSpyBean private ControleAdministracaoPort controle;

    private ContasPort contasSpy;
    private ControleAdministracaoPort controleSpy;

    @BeforeEach
    void prepararCenario() {
        contasSpy = AopTestUtils.getUltimateTargetObject(contas);
        controleSpy = AopTestUtils.getUltimateTargetObject(controle);
        reset(contasSpy, controleSpy);
        limparBancoDeTeste();
    }

    @AfterEach
    void limparCenario() {
        reset(contasSpy, controleSpy);
        limparBancoDeTeste();
    }

    @ParameterizedTest
    @EnumSource(value = Papel.class, names = { "COMPRADOR", "ARTESAO" })
    void usuarioComumNaoPromoveOutroUsuario(Papel papel) {
        Conta solicitante = criarConta(papel, false);
        Conta alvo = criarConta(Papel.COMPRADOR, true);
        criarConta(Papel.ADMIN, false);

        Throwable falha = catchThrowable(() -> alterarPapel.alterar(identidade(solicitante), comando(alvo, Papel.ADMIN)));

        conferirAcessoNegado(falha, AcessoNegadoException.Motivo.PRIVILEGIO_INSUFICIENTE);
        assertThat(recarregar(solicitante)).isEqualTo(solicitante);
        assertThat(recarregar(alvo)).isEqualTo(alvo);
        assertThat(quantidadeAdministradores()).isEqualTo(1);
        verify(contasSpy, never()).atualizarPapel(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = Papel.class, names = { "COMPRADOR", "ARTESAO" })
    void administradorAutorizadoPromoveContaSemAlterarCredenciaisOuIndicador(Papel papelAnterior) {
        Conta administrador = criarConta(Papel.ADMIN, false);
        Conta alvo = criarConta(papelAnterior, true);

        alterarPapel.alterar(identidade(administrador), comando(alvo, Papel.ADMIN));

        assertThat(recarregar(alvo)).isEqualTo(comPapel(alvo, Papel.ADMIN));
        assertThat(recarregar(administrador)).isEqualTo(administrador);
        assertThat(quantidadeAdministradores()).isEqualTo(2);
        assertThat(bootstrapConcluido()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Papel.class)
    void administradorPodeAtribuirSomenteOPapelDoComandoPreservandoOutrosDados(Papel novoPapel) {
        Conta administrador = criarConta(Papel.ADMIN, false);
        Conta alvo = criarConta(Papel.ARTESAO, true);

        alterarPapel.alterar(identidade(administrador), comando(alvo, novoPapel));

        assertThat(recarregar(alvo)).isEqualTo(comPapel(alvo, novoPapel));
        assertThat(recarregar(administrador)).isEqualTo(administrador);
    }

    @Test
    void administradorComTrocaPendenteNaoPromoveNinguem() {
        Conta administrador = criarConta(Papel.ADMIN, true);
        Conta alvo = criarConta(Papel.COMPRADOR, false);

        Throwable falha = catchThrowable(() -> alterarPapel.alterar(identidade(administrador), comando(alvo, Papel.ADMIN)));

        conferirAcessoNegado(falha, AcessoNegadoException.Motivo.TROCA_SENHA_OBRIGATORIA);
        assertThat(recarregar(alvo)).isEqualTo(alvo);
        assertThat(recarregar(administrador)).isEqualTo(administrador);
    }

    @ParameterizedTest
    @EnumSource(value = Papel.class, names = { "COMPRADOR", "ARTESAO" })
    void ultimoAdministradorNaoConsegueContornarRegraAlterandoOProprioPapel(Papel novoPapel) {
        Conta unico = criarConta(Papel.ADMIN, false);

        assertThatThrownBy(() -> alterarPapel.alterar(identidade(unico), comando(unico, novoPapel)))
                .isInstanceOf(UltimoAdministradorException.class);

        assertThat(recarregar(unico)).isEqualTo(unico);
        assertThat(quantidadeAdministradores()).isEqualTo(1);
        verify(contasSpy, never()).atualizarPapel(any(), any(), any());
    }

    @Test
    void comDoisAdministradoresUmPodeSerDespromovidoEOUltimoFicaProtegido() {
        Conta primeiro = criarConta(Papel.ADMIN, false);
        Conta segundo = criarConta(Papel.ADMIN, false);

        alterarPapel.alterar(identidade(primeiro), comando(segundo, Papel.COMPRADOR));

        assertThat(recarregar(segundo).papel()).isEqualTo(Papel.COMPRADOR);
        assertThat(quantidadeAdministradores()).isEqualTo(1);
        assertThatThrownBy(() -> alterarPapel.alterar(identidade(primeiro), comando(primeiro, Papel.ARTESAO)))
                .isInstanceOf(UltimoAdministradorException.class);
        assertThatThrownBy(() -> alterarPapel.alterar(identidade(segundo), comando(segundo, Papel.ADMIN)))
                .isInstanceOf(AcessoNegadoException.class);
        assertThat(recarregar(primeiro)).isEqualTo(primeiro);
        assertThat(quantidadeAdministradores()).isEqualTo(1);
    }

    @Test
    void autodespromocaoEPermitidaQuandoOutroAdministradorPermanece() {
        Conta primeiro = criarConta(Papel.ADMIN, false);
        Conta segundo = criarConta(Papel.ADMIN, false);

        alterarPapel.alterar(identidade(primeiro), comando(primeiro, Papel.ARTESAO));

        assertThat(recarregar(primeiro)).isEqualTo(comPapel(primeiro, Papel.ARTESAO));
        assertThat(recarregar(segundo)).isEqualTo(segundo);
        assertThatThrownBy(() -> alterarPapel.alterar(identidade(segundo), comando(segundo, Papel.COMPRADOR)))
                .isInstanceOf(UltimoAdministradorException.class);
    }

    @Test
    void manterPapelDoUltimoAdministradorEIdempotenteMasExigeAutorizacao() {
        Conta administrador = criarConta(Papel.ADMIN, false);

        alterarPapel.alterar(identidade(administrador), comando(administrador, Papel.ADMIN));

        assertThat(recarregar(administrador)).isEqualTo(administrador);
        verify(contasSpy, never()).atualizarPapel(any(), any(), any());
        Conta comprador = criarConta(Papel.COMPRADOR, false);
        assertThatThrownBy(() -> alterarPapel.alterar(identidade(comprador), comando(administrador, Papel.ADMIN)))
                .isInstanceOf(AcessoNegadoException.class);
    }

    @Test
    void alvoAdministradorOuNovoPapelAdminNaoServemComoProvaDeAutorizacao() {
        Conta comprador = criarConta(Papel.COMPRADOR, false);
        Conta administrador = criarConta(Papel.ADMIN, false);

        Throwable falha = catchThrowable(() -> alterarPapel.alterar(identidade(comprador), comando(administrador, Papel.ADMIN)));

        conferirAcessoNegado(falha, AcessoNegadoException.Motivo.PRIVILEGIO_INSUFICIENTE);
        assertThat(recarregar(comprador)).isEqualTo(comprador);
        assertThat(recarregar(administrador)).isEqualTo(administrador);
    }

    @Test
    void identidadeAusenteNaoPermiteEscolherSolicitantePeloComando() {
        Conta administrador = criarConta(Papel.ADMIN, false);
        Conta alvo = criarConta(Papel.COMPRADOR, false);

        Throwable falha = catchThrowable(() -> alterarPapel.alterar(null, comando(alvo, Papel.ADMIN)));

        conferirAcessoNegado(falha, AcessoNegadoException.Motivo.NAO_AUTENTICADO);
        assertThat(recarregar(administrador)).isEqualTo(administrador);
        assertThat(recarregar(alvo)).isEqualTo(alvo);
    }

    @Test
    void identificadorDeSolicitanteInexistenteNaoAutorizaPromocao() {
        criarConta(Papel.ADMIN, false);
        Conta alvo = criarConta(Papel.COMPRADOR, false);

        Throwable falha = catchThrowable(() -> alterarPapel.alterar(new IdentidadeAutenticada(UUID.randomUUID()), comando(alvo, Papel.ADMIN)));

        conferirAcessoNegado(falha, AcessoNegadoException.Motivo.CONTA_INDISPONIVEL);
        assertThat(recarregar(alvo)).isEqualTo(alvo);
    }

    @ParameterizedTest(name = "comando invalido [{index}]")
    @MethodSource("comandosInvalidos")
    void comandoInvalidoNaoModificaPapeis(AlterarPapelComando comando) {
        Conta administrador = criarConta(Papel.ADMIN, false);

        Throwable falha = catchThrowable(() -> alterarPapel.alterar(identidade(administrador), comando));

        conferirFalhaPapel(falha, AlteracaoPapelException.Motivo.COMANDO_INVALIDO);
        assertThat(recarregar(administrador)).isEqualTo(administrador);
    }

    @Test
    void contaAlvoInexistenteProduzErroDeNegocioSemAlterarAdministrador() {
        Conta administrador = criarConta(Papel.ADMIN, false);

        Throwable falha = catchThrowable(() -> alterarPapel.alterar(identidade(administrador),
                new AlterarPapelComando(UUID.randomUUID(), Papel.ADMIN)));

        conferirFalhaPapel(falha, AlteracaoPapelException.Motivo.CONTA_ALVO_INDISPONIVEL);
        assertThat(recarregar(administrador)).isEqualTo(administrador);
    }

    @Test
    void solicitanteDespromovidoNoBancoNaoUsaSnapshotOuIdentidadeAntigaComoAutorizacao() {
        Conta antigoAdmin = criarConta(Papel.ADMIN, false);
        criarConta(Papel.ADMIN, false);
        Conta alvo = criarConta(Papel.COMPRADOR, false);
        transacao().executeWithoutResult(status -> {
            assertThat(contas.buscarPorId(antigoAdmin.id()).orElseThrow().papel()).isEqualTo(Papel.ADMIN);
            TransactionTemplate outra = transacao();
            outra.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            outra.executeWithoutResult(interno -> jdbc.update("update contas set papel='comprador' where id=?", antigoAdmin.id()));

            Throwable falha = catchThrowable(() -> alterarPapel.alterar(identidade(antigoAdmin), comando(alvo, Papel.ADMIN)));

            conferirAcessoNegado(falha, AcessoNegadoException.Motivo.PRIVILEGIO_INSUFICIENTE);
        });
        assertThat(recarregar(antigoAdmin).papel()).isEqualTo(Papel.COMPRADOR);
        assertThat(recarregar(alvo)).isEqualTo(alvo);
    }

    @Test
    void bloqueiaSingletonAntesDeAutorizarContarEAtualizarPapel() {
        Conta administrador = criarConta(Papel.ADMIN, false);
        Conta alvo = criarConta(Papel.ADMIN, false);

        alterarPapel.alterar(identidade(administrador), comando(alvo, Papel.COMPRADOR));

        var ordem = inOrder(controleSpy, contasSpy);
        ordem.verify(controleSpy).bloquear();
        ordem.verify(contasSpy).bloquearPorId(administrador.id());
        ordem.verify(contasSpy).bloquearPorId(alvo.id());
        ordem.verify(contasSpy).contarPorPapel(Papel.ADMIN);
        ordem.verify(contasSpy).atualizarPapel(alvo.id(), Papel.ADMIN, Papel.COMPRADOR);
    }

    @Test
    void falhaDepoisDaEscritaRealReverteOPapelSemExporHash() {
        Conta administrador = criarConta(Papel.ADMIN, false);
        Conta alvo = criarConta(Papel.COMPRADOR, true);
        doAnswer(invocacao -> {
            assertThat(invocacao.callRealMethod()).isEqualTo(true);
            assertThat(jdbc.queryForObject("select papel from contas where id=?", String.class, alvo.id())).isEqualTo("admin");
            throw new IllegalStateException("Falha sintetica " + HASH.valor());
        }).when(contasSpy).atualizarPapel(any(UUID.class), any(Papel.class), any(Papel.class));

        Throwable falha = catchThrowable(() -> alterarPapel.alterar(identidade(administrador), comando(alvo, Papel.ADMIN)));

        conferirFalhaPapel(falha, AlteracaoPapelException.Motivo.FALHA_ALTERACAO);
        assertThat(falha).hasNoCause();
        var texto = new StringWriter();
        falha.printStackTrace(new PrintWriter(texto));
        assertThat(texto.toString()).doesNotContain(HASH.valor());
        assertThat(recarregar(alvo)).isEqualTo(alvo);
        assertThat(recarregar(administrador)).isEqualTo(administrador);
        assertThat(quantidadeAdministradores()).isEqualTo(1);
    }

    @Test
    void atualizacaoCondicionalSemLinhaAlteradaNaoConfirmaPromocao() {
        Conta administrador = criarConta(Papel.ADMIN, false);
        Conta alvo = criarConta(Papel.COMPRADOR, false);
        doReturn(false).when(contasSpy).atualizarPapel(any(UUID.class), any(Papel.class), any(Papel.class));

        Throwable falha = catchThrowable(() -> alterarPapel.alterar(identidade(administrador), comando(alvo, Papel.ADMIN)));

        conferirFalhaPapel(falha, AlteracaoPapelException.Motivo.CONFLITO_ATUALIZACAO);
        assertThat(recarregar(alvo)).isEqualTo(alvo);
    }

    @Test
    void comandoNaoRecebePapelOuIdentificadorDoSolicitanteComoCredencial() {
        assertThat(Arrays.stream(AlterarPapelComando.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("contaAlvoId", "novoPapel");
        assertThat(Arrays.stream(IdentidadeAutenticada.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("contaId");
    }

    @ParameterizedTest(name = "duas autodespromocoes; transacao externa repeatable read = {0}")
    @ValueSource(booleans = { false, true })
    void operacoesSimultaneasUsamTravaRealENaoEliminamTodosOsAdministradores(boolean transacoesExternas) throws Exception {
        Conta primeiro = criarConta(Papel.ADMIN, false);
        Conta segundo = criarConta(Papel.ADMIN, false);
        CountDownLatch primeiraBloqueou = new CountDownLatch(1);
        CountDownLatch segundaChegou = new CountDownLatch(1);
        CountDownLatch liberarPrimeira = new CountDownLatch(1);
        CyclicBarrier snapshotsProntos = new CyclicBarrier(2);
        AtomicInteger chamadas = new AtomicInteger();
        AtomicInteger primeiraConexao = new AtomicInteger();
        AtomicInteger segundaConexao = new AtomicInteger();
        Set<Integer> conexoesExternas = ConcurrentHashMap.newKeySet();
        Set<Integer> conexoesInternas = ConcurrentHashMap.newKeySet();
        doAnswer(invocacao -> {
            int numero = chamadas.incrementAndGet();
            int conexao = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
            conexoesInternas.add(conexao);
            assertThat(jdbc.queryForObject("show transaction_isolation", String.class)).isEqualTo("read committed");
            if (numero == 1) {
                primeiraConexao.set(conexao);
                Object controleLido = invocacao.callRealMethod();
                primeiraBloqueou.countDown();
                aguardar(liberarPrimeira);
                return controleLido;
            }
            segundaConexao.set(conexao);
            segundaChegou.countDown();
            return invocacao.callRealMethod();
        }).when(controleSpy).bloquear();

        List<Object> resultados;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var uma = executor.submit(() -> tentarAutodespromover(primeiro, transacoesExternas, snapshotsProntos, conexoesExternas));
            var outra = executor.submit(() -> tentarAutodespromover(segundo, transacoesExternas, snapshotsProntos, conexoesExternas));
            try {
                assertThat(primeiraBloqueou.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(segundaChegou.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(aguardarBloqueioNoPostgres(segundaConexao.get(), primeiraConexao.get())).isTrue();
                liberarPrimeira.countDown();
                resultados = List.of(uma.get(30, TimeUnit.SECONDS), outra.get(30, TimeUnit.SECONDS));
            } finally {
                liberarPrimeira.countDown();
            }
        }

        assertThat(conexoesInternas).hasSize(2);
        if (transacoesExternas) {
            assertThat(conexoesExternas).hasSize(2).doesNotContainAnyElementsOf(conexoesInternas);
        }
        assertThat(resultados.stream().filter(Boolean.TRUE::equals).toList()).hasSize(1);
        assertThat(resultados.stream().filter(Throwable.class::isInstance).toList())
                .singleElement().isInstanceOf(UltimoAdministradorException.class);
        assertThat(quantidadeAdministradores()).isEqualTo(1);
        assertThat(List.of(recarregar(primeiro).papel(), recarregar(segundo).papel()))
                .containsExactlyInAnyOrder(Papel.ADMIN, Papel.COMPRADOR);
    }

    private Object tentarAutodespromover(Conta conta, boolean transacaoExterna, CyclicBarrier snapshots, Set<Integer> conexoesExternas) {
        if (!transacaoExterna) return tentarAlterar(conta);
        TransactionTemplate externa = transacao();
        externa.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return externa.execute(status -> {
            conexoesExternas.add(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
            assertThat(quantidadeAdministradores()).isEqualTo(2);
            try { snapshots.await(15, TimeUnit.SECONDS); }
            catch (Exception exception) { throw new IllegalStateException("Falha sincronizando snapshots de teste", exception); }
            return tentarAlterar(conta);
        });
    }

    private Object tentarAlterar(Conta conta) {
        try { alterarPapel.alterar(identidade(conta), comando(conta, Papel.COMPRADOR)); return Boolean.TRUE; }
        catch (RuntimeException exception) { return exception; }
    }

    private boolean aguardarBloqueioNoPostgres(int conexaoBloqueada, int conexaoBloqueadora) {
        long limite = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            if (Boolean.TRUE.equals(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))", Boolean.class,
                    conexaoBloqueadora, conexaoBloqueada))) return true;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        } while (System.nanoTime() < limite);
        return false;
    }

    private void aguardar(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Tempo esgotado na transacao concorrente de teste");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Conta criarConta(Papel papel, boolean trocaObrigatoria) {
        return contas.salvar(new Conta(UUID.randomUUID(), "Conta de teste", new Email(UUID.randomUUID() + "@example.test"),
                HASH, papel, trocaObrigatoria));
    }
    private Conta recarregar(Conta conta) { return contas.buscarPorId(conta.id()).orElseThrow(); }
    private Conta comPapel(Conta original, Papel novo) {
        return new Conta(original.id(), original.nome(), original.email(), original.senhaProtegida(), novo, original.trocaSenhaObrigatoria());
    }
    private IdentidadeAutenticada identidade(Conta conta) { return new IdentidadeAutenticada(conta.id()); }
    private AlterarPapelComando comando(Conta alvo, Papel papel) { return new AlterarPapelComando(alvo.id(), papel); }
    private long quantidadeAdministradores() { return jdbc.queryForObject("select count(*) from contas where papel='admin'", Long.class); }
    private boolean bootstrapConcluido() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select bootstrap_concluido from controle_administracao where id=1", Boolean.class));
    }
    private TransactionTemplate transacao() { return new TransactionTemplate(transactionManager); }
    private void limparBancoDeTeste() {
        jdbc.update("delete from contas");
        jdbc.update("update controle_administracao set bootstrap_concluido=false where id=1");
    }
    private void conferirAcessoNegado(Throwable falha, AcessoNegadoException.Motivo motivo) {
        assertThat(falha).isInstanceOf(AcessoNegadoException.class);
        assertThat(((AcessoNegadoException) falha).motivo()).isEqualTo(motivo);
    }
    private void conferirFalhaPapel(Throwable falha, AlteracaoPapelException.Motivo motivo) {
        assertThat(falha).isInstanceOf(AlteracaoPapelException.class);
        assertThat(((AlteracaoPapelException) falha).motivo()).isEqualTo(motivo);
    }
    private static Stream<Arguments> comandosInvalidos() {
        return Stream.of(Arguments.of((Object) null),
                Arguments.of(new AlterarPapelComando(null, Papel.ADMIN)),
                Arguments.of(new AlterarPapelComando(UUID.randomUUID(), null)));
    }
}
