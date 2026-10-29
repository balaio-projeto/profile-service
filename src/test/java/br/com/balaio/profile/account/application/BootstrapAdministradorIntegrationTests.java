package br.com.balaio.profile.account.application;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.account.application.port.out.CodificarSenhaPort;
import br.com.balaio.profile.account.application.port.out.ConfiguracaoAdministradorInicialPort;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.application.port.out.ControleAdministracaoPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.SenhaProtegida;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({ PostgreSQLTestConfiguration.class, BootstrapAdministradorIntegrationTests.CredenciaisDeTesteConfiguration.class })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BootstrapAdministradorIntegrationTests {

	private static final String EMAIL = "bootstrap.integration@example.test";
	private static final String SENHA = "Aa9!" + UUID.randomUUID();
	private static final String HASH_FIXTURE = "{bcrypt}$2b$12$" + ".".repeat(53);

	@Autowired
	private InicializarAdministradorUseCase inicializarAdministrador;

	@Autowired
	private CredenciaisDeTeste credenciais;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbc;

	@MockitoSpyBean
	private ContasPort contas;

	@MockitoSpyBean
	private ControleAdministracaoPort controle;

	@MockitoSpyBean
	private CodificarSenhaPort senhas;

	private ContasPort contasSpy;
	private ControleAdministracaoPort controleSpy;
	private CodificarSenhaPort senhasSpy;

	private Conta administradorCriadoNaInicializacao;
	private boolean bootstrapConcluidoNaInicializacao;
	private long quantidadeContasNaInicializacao;

	@BeforeAll
	void capturarResultadoDoRunnerReal() {
		contasSpy = AopTestUtils.getUltimateTargetObject(contas);
		controleSpy = AopTestUtils.getUltimateTargetObject(controle);
		senhasSpy = AopTestUtils.getUltimateTargetObject(senhas);
		administradorCriadoNaInicializacao = contas.buscarPorEmail(new Email(EMAIL)).orElse(null);
		bootstrapConcluidoNaInicializacao = bootstrapConcluido();
		quantidadeContasNaInicializacao = quantidadeContas();
	}

	@BeforeEach
	void prepararCenario() {
		reset(contasSpy, controleSpy, senhasSpy);
		limparBancoDeTeste();
		credenciais.definir(new ConfiguracaoAdministradorInicial(EMAIL, SENHA));
	}

	@AfterEach
	void limparCenario() {
		reset(contasSpy, controleSpy, senhasSpy);
		limparBancoDeTeste();
	}

	@Test
	void runnerCriaAdministradorAutomaticamenteAoIniciarAplicacao() {
		assertThat(quantidadeContasNaInicializacao).isEqualTo(1);
		assertThat(bootstrapConcluidoNaInicializacao).isTrue();
		assertThat(administradorCriadoNaInicializacao).isNotNull();
		assertThat(administradorCriadoNaInicializacao.papel()).isEqualTo(Papel.ADMIN);
		assertThat(administradorCriadoNaInicializacao.trocaSenhaObrigatoria()).isTrue();
		assertThat(passwordEncoder.matches(SENHA, administradorCriadoNaInicializacao.senhaProtegida().valor())).isTrue();
	}

	@Test
	void primeiraExecucaoCriaUmaContaAdminComHashETrocaObrigatoria() {
		credenciais.definir(new ConfiguracaoAdministradorInicial("  BOOTSTRAP.INTEGRATION@EXAMPLE.TEST  ", SENHA));

		inicializarAdministrador.inicializar();

		Conta administrador = administrador();
		assertThat(administrador.id()).isNotNull();
		assertThat(administrador.nome()).isEqualTo("Administrador inicial");
		assertThat(administrador.email().valor()).isEqualTo(EMAIL);
		assertThat(administrador.papel()).isEqualTo(Papel.ADMIN);
		assertThat(administrador.trocaSenhaObrigatoria()).isTrue();
		assertThat(administrador.senhaProtegida().valor()).startsWith("{bcrypt}$2").doesNotContain(SENHA);
		assertThat(passwordEncoder.matches(SENHA, administrador.senhaProtegida().valor())).isTrue();
		assertThat(bootstrapConcluido()).isTrue();
		assertThat(quantidadeContas()).isEqualTo(1);
	}

	@Test
	void segundaExecucaoPreservaIdentidadeHashEQuantidade() {
		inicializarAdministrador.inicializar();
		Conta primeira = administrador();

		inicializarAdministrador.inicializar();

		assertThat(administrador()).isEqualTo(primeira);
		assertThat(quantidadeContas()).isEqualTo(1);
		assertThat(credenciais.leituras()).isEqualTo(1);
		verify(senhasSpy, times(1)).codificar(anyString());
	}

	@Test
	void terceiraExecucaoNaoRedefineSenhaQueJaFoiAlterada() {
		inicializarAdministrador.inicializar();
		Conta primeira = administrador();
		String novaSenha = "Bb8!" + UUID.randomUUID();
		Conta atualizada = new Conta(primeira.id(), primeira.nome(), primeira.email(),
				new SenhaProtegida(passwordEncoder.encode(novaSenha)), Papel.ADMIN, false);
		contas.salvar(atualizada);

		inicializarAdministrador.inicializar();
		inicializarAdministrador.inicializar();

		Conta preservada = administrador();
		assertThat(preservada).isEqualTo(atualizada);
		assertThat(passwordEncoder.matches(novaSenha, preservada.senhaProtegida().valor())).isTrue();
		assertThat(passwordEncoder.matches(SENHA, preservada.senhaProtegida().valor())).isFalse();
		assertThat(preservada.trocaSenhaObrigatoria()).isFalse();
		assertThat(quantidadeContas()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(Papel.class)
	void emailOcupadoNaoRecebePromocaoNemSubstituicaoDeCredenciais(Papel papel) {
		Conta existente = fixture(EMAIL, papel);
		contas.salvar(existente);

		assertThatThrownBy(inicializarAdministrador::inicializar)
				.isInstanceOf(BootstrapAdministradorException.class)
				.hasMessageNotContaining(EMAIL)
				.hasMessageNotContaining(SENHA)
				.hasNoCause();

		assertThat(administrador()).isEqualTo(existente);
		assertThat(quantidadeContas()).isEqualTo(1);
		assertThat(bootstrapConcluido()).isFalse();
		verify(senhasSpy, never()).codificar(anyString());
	}

	@Test
	void outroAdministradorPreexistenteImpedeNovoBootstrap() {
		Conta existente = fixture("outro.admin@example.test", Papel.ADMIN);
		contas.salvar(existente);

		assertThatThrownBy(inicializarAdministrador::inicializar)
				.isInstanceOf(BootstrapAdministradorException.class).hasNoCause();

		assertThat(contas.buscarPorId(existente.id())).contains(existente);
		assertThat(contas.buscarPorEmail(new Email(EMAIL))).isEmpty();
		assertThat(quantidadeContas()).isEqualTo(1);
		assertThat(bootstrapConcluido()).isFalse();
		verify(senhasSpy, never()).codificar(anyString());
	}

	@ParameterizedTest
	@ValueSource(strings = { "depois-de-salvar", "depois-de-concluir" })
	void falhaReverteContaEMarcadorMesmoAposEscritasReais(String pontoDeFalha) {
		if (pontoDeFalha.equals("depois-de-salvar")) {
			doAnswer(invocacao -> {
				invocacao.callRealMethod();
				throw new IllegalStateException("Falha sintetica depois de persistir conta");
			}).when(contasSpy).salvar(any(Conta.class));
		} else {
			doAnswer(invocacao -> {
				invocacao.callRealMethod();
				throw new IllegalStateException("Falha sintetica depois de persistir marcador");
			}).when(controleSpy).concluirBootstrap();
		}

		assertThatThrownBy(inicializarAdministrador::inicializar).isInstanceOf(RuntimeException.class);

		assertThat(quantidadeContas()).isZero();
		assertThat(bootstrapConcluido()).isFalse();
	}

	@ParameterizedTest(name = "configuracao invalida [{index}]")
	@MethodSource("configuracoesInvalidas")
	void configuracaoInvalidaInterrompeSemPersistirContaOuMarcador(String email, String senha) {
		credenciais.definir(new ConfiguracaoAdministradorInicial(email, senha));

		Throwable falha = catchThrowable(inicializarAdministrador::inicializar);

		assertThat(falha).isInstanceOf(BootstrapAdministradorException.class).hasNoCause();
		if (email != null && !email.isBlank()) {
			assertThat(falha.getMessage()).doesNotContain(email);
		}
		if (senha != null && !senha.isBlank()) {
			assertThat(falha.getMessage()).doesNotContain(senha);
		}
		assertThat(quantidadeContas()).isZero();
		assertThat(bootstrapConcluido()).isFalse();
		verify(senhasSpy, never()).codificar(anyString());
	}

	@ParameterizedTest(name = "configuracao ignorada apos conclusao [{index}]")
	@MethodSource("configuracoesIgnoradasAposConclusao")
	void bootstrapConcluidoIgnoraVariaveisAusentesInvalidasOuAlteradas(String email, String senha) {
		inicializarAdministrador.inicializar();
		Conta primeira = administrador();
		credenciais.definir(new ConfiguracaoAdministradorInicial(email, senha));

		inicializarAdministrador.inicializar();

		assertThat(administrador()).isEqualTo(primeira);
		assertThat(quantidadeContas()).isEqualTo(1);
		assertThat(bootstrapConcluido()).isTrue();
		assertThat(credenciais.leituras()).isZero();
		verify(senhasSpy, times(1)).codificar(anyString());
	}

	@Test
	void marcadorConcluidoNaoRestauraPapelAlteradoPosteriormente() {
		inicializarAdministrador.inicializar();
		Conta primeira = administrador();
		Conta alterada = new Conta(primeira.id(), primeira.nome(), primeira.email(),
				primeira.senhaProtegida(), Papel.COMPRADOR, false);
		contas.salvar(alterada);

		inicializarAdministrador.inicializar();

		assertThat(administrador()).isEqualTo(alterada);
		assertThat(contas.contarPorPapel(Papel.ADMIN)).isZero();
		assertThat(bootstrapConcluido()).isTrue();
		assertThat(quantidadeContas()).isEqualTo(1);
	}

	@Test
	void senhaNaoSofreTrimAntesDaCodificacao() {
		String senhaComEspacos = " " + SENHA + " ";
		credenciais.definir(new ConfiguracaoAdministradorInicial(EMAIL, senhaComEspacos));

		inicializarAdministrador.inicializar();

		String hash = administrador().senhaProtegida().valor();
		assertThat(passwordEncoder.matches(senhaComEspacos, hash)).isTrue();
		assertThat(passwordEncoder.matches(SENHA, hash)).isFalse();
	}

	@Test
	void duasInicializacoesConcorrentesUsamBloqueioDoPostgresECriamUmaUnicaConta() throws Exception {
		CountDownLatch primeiraBloqueou = new CountDownLatch(1);
		CountDownLatch liberarPrimeira = new CountDownLatch(1);
		CountDownLatch segundaIniciou = new CountDownLatch(1);
		AtomicInteger chamadas = new AtomicInteger();
		AtomicInteger primeiraConexao = new AtomicInteger();
		AtomicInteger segundaConexao = new AtomicInteger();
		doAnswer(invocacao -> {
			int numero = chamadas.incrementAndGet();
			int conexao = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
			if (numero == 1) {
				primeiraConexao.set(conexao);
				Object resultado = invocacao.callRealMethod();
				primeiraBloqueou.countDown();
				aguardar(liberarPrimeira);
				return resultado;
			}
			segundaConexao.set(conexao);
			segundaIniciou.countDown();
			return invocacao.callRealMethod();
		}).when(controleSpy).bloquear();

		try (var executor = Executors.newFixedThreadPool(2)) {
			var primeira = executor.submit(inicializarAdministrador::inicializar);
			try {
				assertThat(primeiraBloqueou.await(10, TimeUnit.SECONDS)).isTrue();
				var segunda = executor.submit(inicializarAdministrador::inicializar);
				assertThat(segundaIniciou.await(10, TimeUnit.SECONDS)).isTrue();
				assertThat(aguardarBloqueioNoPostgres(segundaConexao.get(), primeiraConexao.get())).isTrue();
				assertThat(segunda.isDone()).isFalse();
				liberarPrimeira.countDown();
				primeira.get(15, TimeUnit.SECONDS);
				segunda.get(15, TimeUnit.SECONDS);
			} finally {
				liberarPrimeira.countDown();
			}
		}

		assertThat(quantidadeContas()).isEqualTo(1);
		assertThat(contas.contarPorPapel(Papel.ADMIN)).isEqualTo(1);
		assertThat(bootstrapConcluido()).isTrue();
		assertThat(administrador().trocaSenhaObrigatoria()).isTrue();
		assertThat(credenciais.leituras()).isEqualTo(1);
		verify(senhasSpy, times(1)).codificar(anyString());
	}

	private static Stream<Arguments> configuracoesInvalidas() {
		return Stream.of(
				Arguments.of(null, null),
				Arguments.of(null, SENHA),
				Arguments.of(EMAIL, null),
				Arguments.of("", SENHA),
				Arguments.of("  ", SENHA),
				Arguments.of("email-invalido", SENHA),
				Arguments.of(EMAIL, ""),
				Arguments.of(EMAIL, " ".repeat(12)),
				Arguments.of(EMAIL, "curta"),
				Arguments.of(EMAIL, "\uD83D\uDD12".repeat(11)),
				Arguments.of(EMAIL, "a".repeat(73)),
				Arguments.of(EMAIL, "é".repeat(37)),
				Arguments.of(EMAIL, "Aa9!senha-com\ncontrole"));
	}

	private static Stream<Arguments> configuracoesIgnoradasAposConclusao() {
		return Stream.of(
				Arguments.of(null, null),
				Arguments.of(null, SENHA),
				Arguments.of(EMAIL, null),
				Arguments.of("email-invalido", "curta"),
				Arguments.of("novo.admin@example.test", "Cc7!" + UUID.randomUUID()));
	}

	private Conta administrador() {
		return contas.buscarPorEmail(new Email(EMAIL)).orElseThrow();
	}

	private Conta fixture(String email, Papel papel) {
		return new Conta(UUID.randomUUID(), "Conta preexistente", new Email(email),
				new SenhaProtegida(HASH_FIXTURE), papel, false);
	}

	private long quantidadeContas() {
		return jdbc.queryForObject("select count(*) from contas", Long.class);
	}

	private boolean bootstrapConcluido() {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"select bootstrap_concluido from controle_administracao where id = 1", Boolean.class));
	}

	private void limparBancoDeTeste() {
		jdbc.update("delete from contas");
		jdbc.update("update controle_administracao set bootstrap_concluido = false where id = 1");
	}

	private boolean aguardarBloqueioNoPostgres(int conexaoBloqueada, int conexaoBloqueadora) {
		long limite = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		do {
			Boolean bloqueada = jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",
					Boolean.class, conexaoBloqueadora, conexaoBloqueada);
			if (Boolean.TRUE.equals(bloqueada)) {
				return true;
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
		} while (System.nanoTime() < limite);
		return false;
	}

	private void aguardar(CountDownLatch latch) {
		try {
			if (!latch.await(15, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Tempo esgotado aguardando transacao concorrente do teste");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class CredenciaisDeTesteConfiguration {
		@Bean
		@Primary
		CredenciaisDeTeste credenciaisDeTeste() {
			return new CredenciaisDeTeste();
		}
	}

	static final class CredenciaisDeTeste implements ConfiguracaoAdministradorInicialPort {
		private volatile ConfiguracaoAdministradorInicial configuracao = new ConfiguracaoAdministradorInicial(EMAIL, SENHA);
		private final AtomicInteger leituras = new AtomicInteger();

		@Override
		public ConfiguracaoAdministradorInicial obter() {
			leituras.incrementAndGet();
			return configuracao;
		}

		void definir(ConfiguracaoAdministradorInicial configuracao) {
			this.configuracao = configuracao;
			leituras.set(0);
		}

		int leituras() {
			return leituras.get();
		}
	}
}
