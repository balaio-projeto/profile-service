package br.com.balaio.profile.account.persistence;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.account.application.port.out.ControleAdministracaoPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.EmailJaCadastradoException;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.SenhaProtegida;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgreSQLTestConfiguration.class)
class ContasPersistenceTests {

	@MockitoBean(enforceOverride = true)
	private InicializarAdministradorUseCase inicializarAdministrador;

	private static final String HASH = "{bcrypt}$2b$12$" + ".".repeat(53);

	@Autowired
	private ContasPort contas;

	@Autowired
	private ControleAdministracaoPort controle;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@BeforeEach
	@AfterEach
	void limparDadosDeTeste() {
		jdbc.update("delete from contas");
		jdbc.update("update controle_administracao set bootstrap_concluido = false where id = 1");
	}

	@ParameterizedTest
	@EnumSource(Papel.class)
	void persisteContaERecuperaTodosOsCampos(Papel papel) {
		Conta conta = conta("  Maria.Silva@EXAMPLE.COM  ", papel, true);

		Conta salva = transacao().execute(status -> contas.salvar(conta));

		assertThat(salva).isEqualTo(conta);
		assertThat(contas.buscarPorId(conta.id())).contains(conta);
		assertThat(contas.buscarPorEmail(new Email("MARIA.SILVA@example.com"))).contains(conta);
		assertThat(contas.contarPorPapel(papel)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select email from contas where id = ?", String.class, conta.id()))
				.isEqualTo("maria.silva@example.com");
		assertThat(jdbc.queryForObject("select papel from contas where id = ?", String.class, conta.id()))
				.isEqualTo(papel.codigo());
		assertThat(jdbc.queryForObject("select senha_hash from contas where id = ?", String.class, conta.id()))
				.isEqualTo(HASH);
	}

	@Test
	void retornaAusenciaQuandoContaNaoExiste() {
		assertThat(contas.buscarPorId(UUID.randomUUID())).isEmpty();
		assertThat(contas.buscarPorEmail(new Email("ausente@example.com"))).isEmpty();
		assertThat(contas.contarPorPapel(Papel.ADMIN)).isZero();
	}

	@Test
	void preservaIdentidadeAoAtualizarMesmaConta() {
		Conta original = conta("maria@example.com", Papel.ARTESAO, true);
		transacao().executeWithoutResult(status -> contas.salvar(original));
		Conta atualizada = new Conta(original.id(), "Maria Oliveira", new Email("maria.oliveira@example.com"),
				new SenhaProtegida(HASH), Papel.ARTESAO, false);

		transacao().executeWithoutResult(status -> contas.salvar(atualizada));

		assertThat(contas.buscarPorId(original.id())).contains(atualizada);
		assertThat(contas.buscarPorEmail(original.email())).isEmpty();
		assertThat(contas.contarPorPapel(Papel.ARTESAO)).isEqualTo(1);
	}

	@Test
	void rejeitaEmailDuplicadoAposNormalizacao() {
		transacao().executeWithoutResult(status ->
				contas.salvar(conta("maria@example.com", Papel.COMPRADOR, false)));

		assertThatThrownBy(() -> transacao().executeWithoutResult(status ->
				contas.salvar(conta(" MARIA@EXAMPLE.COM ", Papel.ARTESAO, false))))
				.isInstanceOf(EmailJaCadastradoException.class);
		assertThat(jdbc.queryForObject("select count(*) from contas", Long.class)).isEqualTo(1L);
	}

	@Test
	void bancoTambemImpedeEmailDuplicadoSemPassarPeloDominio() {
		inserirDiretamente("maria@example.com", HASH, "comprador");

		assertThatThrownBy(() -> inserirDiretamente("maria@example.com", HASH, "artesao"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "MARIA@example.com", " maria@example.com", "maria@example.com ", "" })
	void bancoExigeEmailNormalizadoENaoVazio(String email) {
		assertThatThrownBy(() -> inserirDiretamente(email, HASH, "comprador"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "ADMIN", "vendedor", "", "comprador,admin" })
	void bancoRejeitaPapeisForaDoContrato(String papel) {
		assertThatThrownBy(() -> inserirDiretamente("maria@example.com", HASH, papel))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "senha-em-texto-puro", "{noop}senha", "$2b$12$sem-identificador", "" })
	void bancoRejeitaSenhaSemFormatoProtegido(String senha) {
		assertThatThrownBy(() -> inserirDiretamente("maria@example.com", senha, "comprador"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void bancoExigeCamposObrigatorios() {
		assertThatThrownBy(() -> jdbc.update("""
				insert into contas (id, nome, email, senha_hash, papel)
				values (null, ?, ?, ?, ?)
				""", "Maria Silva", "maria@example.com", HASH, "comprador"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> inserirDiretamente(null, HASH, "comprador"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> inserirDiretamente("maria@example.com", null, "comprador"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> inserirDiretamente("maria@example.com", HASH, null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("""
				insert into contas (id, nome, email, senha_hash, papel)
				values (?, null, ?, ?, ?)
				""", UUID.randomUUID(), "maria@example.com", HASH, "comprador"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("""
				insert into contas (id, nome, email, senha_hash, papel, troca_senha_obrigatoria)
				values (?, ?, ?, ?, ?, null)
				""", UUID.randomUUID(), "Maria Silva", "maria@example.com", HASH, "comprador"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void contaRecebeTrocaDeSenhaDesativadaQuandoColunaForOmitida() {
		inserirDiretamente("maria@example.com", HASH, "comprador");

		assertThat(jdbc.queryForObject("select troca_senha_obrigatoria from contas", Boolean.class)).isFalse();
	}

	@Test
	void controlePossuiUmaUnicaLinhaValida() {
		assertThatThrownBy(() -> jdbc.update("insert into controle_administracao (id) values (2)"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("insert into controle_administracao (id) values (1)"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbc.queryForObject("select count(*) from controle_administracao", Long.class)).isEqualTo(1L);
	}

	@Test
	void exigeTransacaoParaBloquearOuConcluirControle() {
		assertThatThrownBy(controle::bloquear).isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(controle::concluirBootstrap).isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void controleRejeitaTransacaoSomenteLeitura() {
		TransactionTemplate somenteLeitura = transacao();
		somenteLeitura.setReadOnly(true);

		assertThatThrownBy(() -> somenteLeitura.execute(status -> controle.bloquear()))
				.isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> somenteLeitura.executeWithoutResult(status -> controle.concluirBootstrap()))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void conclusaoDoBootstrapPersisteESuportaRepeticaoSemCriarConta() {
		transacao().executeWithoutResult(status -> {
			assertThat(controle.bloquear().bootstrapConcluido()).isFalse();
			controle.concluirBootstrap();
			controle.concluirBootstrap();
		});

		Boolean concluido = transacao().execute(status -> controle.bloquear().bootstrapConcluido());
		assertThat(concluido).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from contas", Long.class)).isZero();
	}

	@Test
	void rollbackDesfazContaEConclusaoDoBootstrapNaMesmaTransacao() {
		transacao().executeWithoutResult(status -> {
			controle.bloquear();
			contas.salvar(conta("fixture@example.com", Papel.COMPRADOR, false));
			controle.concluirBootstrap();
			status.setRollbackOnly();
		});

		assertThat(contas.buscarPorEmail(new Email("fixture@example.com"))).isEmpty();
		Boolean concluido = transacao().execute(status -> controle.bloquear().bootstrapConcluido());
		assertThat(concluido).isFalse();
	}

	@Test
	void bloqueioSerializaDuasTransacoesEReleEstadoConfirmado() throws Exception {
		CountDownLatch bloqueioObtido = new CountDownLatch(1);
		CountDownLatch liberarPrimeira = new CountDownLatch(1);
		CountDownLatch segundaIniciada = new CountDownLatch(1);
		AtomicInteger primeiraConexao = new AtomicInteger();
		AtomicInteger segundaConexao = new AtomicInteger();

		try (var executor = Executors.newFixedThreadPool(2)) {
			var primeira = executor.submit(() -> transacao().execute(status -> {
				primeiraConexao.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
				controle.bloquear();
				bloqueioObtido.countDown();
				aguardar(liberarPrimeira);
				controle.concluirBootstrap();
				return true;
			}));

			try {
				assertThat(bloqueioObtido.await(10, TimeUnit.SECONDS)).isTrue();
				var segunda = executor.submit(() -> transacao().execute(status -> {
					segundaConexao.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
					segundaIniciada.countDown();
					return controle.bloquear().bootstrapConcluido();
				}));

				assertThat(segundaIniciada.await(10, TimeUnit.SECONDS)).isTrue();
				assertThat(aguardarBloqueioNoPostgres(segundaConexao.get(), primeiraConexao.get())).isTrue();
				assertThat(segunda.isDone()).isFalse();
				liberarPrimeira.countDown();
				assertThat(primeira.get(10, TimeUnit.SECONDS)).isTrue();
				assertThat(segunda.get(10, TimeUnit.SECONDS)).isTrue();
			} finally {
				liberarPrimeira.countDown();
			}
		}
	}

	private boolean aguardarBloqueioNoPostgres(int conexaoBloqueada, int conexaoBloqueadora) {
		long limite = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		do {
			Boolean bloqueada = jdbc.queryForObject(
					"select ? = any(pg_blocking_pids(?))", Boolean.class, conexaoBloqueadora, conexaoBloqueada);
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
				throw new IllegalStateException("Tempo esgotado aguardando a outra transacao do teste");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	private void inserirDiretamente(String email, String hash, String papel) {
		jdbc.update("""
				insert into contas (id, nome, email, senha_hash, papel)
				values (?, ?, ?, ?, ?)
				""", UUID.randomUUID(), "Maria Silva", email, hash, papel);
	}

	private Conta conta(String email, Papel papel, boolean trocaObrigatoria) {
		return new Conta(UUID.randomUUID(), "Maria Silva", new Email(email), new SenhaProtegida(HASH),
				papel, trocaObrigatoria);
	}

	private TransactionTemplate transacao() {
		return new TransactionTemplate(transactionManager);
	}
}
