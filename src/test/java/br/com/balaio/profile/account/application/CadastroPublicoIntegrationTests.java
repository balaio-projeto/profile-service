package br.com.balaio.profile.account.application;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.port.in.CadastrarContaPublicaUseCase;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.EmailJaCadastradoException;
import br.com.balaio.profile.account.domain.Papel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgreSQLTestConfiguration.class)
class CadastroPublicoIntegrationTests {

	private static final String EMAIL = "comprador.integration@example.test";
	private static final String SENHA = "Aa9!" + UUID.randomUUID();

	@MockitoBean(enforceOverride = true)
	private InicializarAdministradorUseCase inicializarAdministrador;

	@Autowired
	private CadastrarContaPublicaUseCase cadastrarContaPublica;

	@Autowired
	private ContasPort contas;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	@AfterEach
	void limparDadosDeTeste() {
		jdbc.update("delete from contas");
		jdbc.update("update controle_administracao set bootstrap_concluido = false where id = 1");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {
			"", "comprador", "artesao", "admin", "ADMIN", "AdMiN", " admin ", "ROLE_ADMIN", "[\"admin\"]",
			"\u0430dmin", "\uff41\uff44\uff4d\uff49\uff4e"
	})
	void cadastroPublicoSemprePersisteCompradorIndependentementeDoPapelEnviado(String papelSolicitado) {
		ContaPublicaCadastrada resultado = cadastrarContaPublica.cadastrar(
				new CadastrarContaPublicaComando("Maria Silva", EMAIL, SENHA, papelSolicitado));

		Conta persistida = contas.buscarPorId(resultado.id()).orElseThrow();
		assertThat(resultado.papel()).isEqualTo(Papel.COMPRADOR);
		assertThat(persistida.papel()).isEqualTo(Papel.COMPRADOR);
		assertThat(persistida.trocaSenhaObrigatoria()).isFalse();
		assertThat(contas.contarPorPapel(Papel.COMPRADOR)).isEqualTo(1);
		assertThat(contas.contarPorPapel(Papel.ARTESAO)).isZero();
		assertThat(contas.contarPorPapel(Papel.ADMIN)).isZero();
		assertThat(jdbc.queryForObject("select papel from contas where id = ?", String.class, resultado.id()))
				.isEqualTo("comprador");
		assertThat(passwordEncoder.matches(SENHA, persistida.senhaProtegida().valor())).isTrue();
		assertThat(quantidadeContas()).isEqualTo(1);
		assertThat(bootstrapConcluido()).isFalse();
	}

	@Test
	void normalizaNomeEEmailESalvaSenhaProtegidaComSaltIndividual() {
		ContaPublicaCadastrada primeira = cadastrarContaPublica.cadastrar(
				new CadastrarContaPublicaComando("  Maria Silva  ", "  COMPRADOR.INTEGRATION@EXAMPLE.TEST  ", SENHA));
		ContaPublicaCadastrada segunda = cadastrarContaPublica.cadastrar(
				new CadastrarContaPublicaComando("Joao Silva", "outro.comprador@example.test", SENHA));

		Conta primeiraConta = contas.buscarPorId(primeira.id()).orElseThrow();
		Conta segundaConta = contas.buscarPorId(segunda.id()).orElseThrow();
		assertThat(primeira.id()).isNotNull().isNotEqualTo(segunda.id());
		assertThat(primeiraConta.nome()).isEqualTo("Maria Silva");
		assertThat(primeiraConta.email()).isEqualTo(new Email(EMAIL));
		assertThat(primeiraConta.senhaProtegida().valor()).startsWith("{bcrypt}$2").contains("$12$")
				.isNotEqualTo(segundaConta.senhaProtegida().valor()).doesNotContain(SENHA);
		assertThat(passwordEncoder.matches(SENHA, primeiraConta.senhaProtegida().valor())).isTrue();
		assertThat(passwordEncoder.matches(SENHA, segundaConta.senhaProtegida().valor())).isTrue();
		assertThat(jdbc.queryForObject("select email from contas where id = ?", String.class, primeira.id()))
				.isEqualTo(EMAIL);
		assertThat(quantidadeContas()).isEqualTo(2);
		assertThat(bootstrapConcluido()).isFalse();
	}

	@Test
	void rejeitaEmailCanonicoDuplicadoSemAlterarContaPreexistente() {
		ContaPublicaCadastrada resultado = cadastrarContaPublica.cadastrar(
				new CadastrarContaPublicaComando("Maria Silva", EMAIL, SENHA));
		Conta original = contas.buscarPorId(resultado.id()).orElseThrow();

		assertThatThrownBy(() -> cadastrarContaPublica.cadastrar(new CadastrarContaPublicaComando(
				"Tentativa de sobrescrita", "  COMPRADOR.INTEGRATION@EXAMPLE.TEST  ",
				"Bb8!" + UUID.randomUUID(), "admin")))
				.isInstanceOf(EmailJaCadastradoException.class).hasNoCause();

		assertThat(contas.buscarPorId(resultado.id())).contains(original);
		assertThat(contas.contarPorPapel(Papel.ADMIN)).isZero();
		assertThat(quantidadeContas()).isEqualTo(1);
		assertThat(bootstrapConcluido()).isFalse();
	}

	@Test
	void resultadoDoCadastroExpoeSomenteIdentificadorEPapel() {
		ContaPublicaCadastrada resultado = cadastrarContaPublica.cadastrar(
				new CadastrarContaPublicaComando("Maria Silva", EMAIL, SENHA));

		assertThat(ContaPublicaCadastrada.class.isRecord()).isTrue();
		assertThat(Arrays.stream(ContaPublicaCadastrada.class.getRecordComponents()).map(componente -> componente.getName()))
				.containsExactly("id", "papel");
		assertThat(resultado.id()).isNotNull();
		assertThat(resultado.papel()).isEqualTo(Papel.COMPRADOR);
		assertThat(resultado.toString()).doesNotContain(SENHA, EMAIL,
				contas.buscarPorId(resultado.id()).orElseThrow().senhaProtegida().valor());
	}

	@ParameterizedTest(name = "entrada invalida [{index}]")
	@MethodSource("entradasInvalidas")
	void entradaInvalidaNaoPersisteContaNemAlteraControle(String nome, String email, String senha) {
		assertThatThrownBy(() -> cadastrarContaPublica.cadastrar(new CadastrarContaPublicaComando(nome, email, senha)))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(quantidadeContas()).isZero();
		assertThat(bootstrapConcluido()).isFalse();
	}

	@Test
	void doisCadastrosConcorrentesParaOMesmoEmailResultamEmUmaUnicaConta() throws Exception {
		CountDownLatch prontos = new CountDownLatch(2);
		CountDownLatch iniciar = new CountDownLatch(1);
		List<Object> resultados;
		try (var executor = Executors.newFixedThreadPool(2)) {
			var primeira = executor.submit(() -> cadastrarConcorrentemente(EMAIL, prontos, iniciar));
			var segunda = executor.submit(() -> cadastrarConcorrentemente("  COMPRADOR.INTEGRATION@EXAMPLE.TEST  ", prontos, iniciar));
			try {
				assertThat(prontos.await(10, TimeUnit.SECONDS)).isTrue();
				iniciar.countDown();
				resultados = List.of(primeira.get(30, TimeUnit.SECONDS), segunda.get(30, TimeUnit.SECONDS));
			} finally {
				iniciar.countDown();
			}
		}

		assertThat(resultados.stream().filter(ContaPublicaCadastrada.class::isInstance).toList()).hasSize(1);
		assertThat(resultados.stream().filter(Throwable.class::isInstance).toList())
				.singleElement().isInstanceOf(EmailJaCadastradoException.class);
		assertThat(quantidadeContas()).isEqualTo(1);
		assertThat(contas.contarPorPapel(Papel.COMPRADOR)).isEqualTo(1);
		assertThat(contas.contarPorPapel(Papel.ADMIN)).isZero();
		assertThat(bootstrapConcluido()).isFalse();
		assertThat(passwordEncoder.matches(SENHA, contas.buscarPorEmail(new Email(EMAIL)).orElseThrow().senhaProtegida().valor()))
				.isTrue();
	}

	private Object cadastrarConcorrentemente(String email, CountDownLatch prontos, CountDownLatch iniciar) {
		prontos.countDown();
		try {
			if (!iniciar.await(15, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Tempo esgotado aguardando inicio do cadastro concorrente no teste");
			}
			return cadastrarContaPublica.cadastrar(new CadastrarContaPublicaComando("Maria Silva", email, SENHA, "admin"));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		} catch (RuntimeException exception) {
			return exception;
		}
	}

	private static Stream<Arguments> entradasInvalidas() {
		return Stream.of(
				Arguments.of(null, EMAIL, SENHA),
				Arguments.of("  ", EMAIL, SENHA),
				Arguments.of("Maria Silva", null, SENHA),
				Arguments.of("Maria Silva", "email-invalido", SENHA),
				Arguments.of("Maria Silva", EMAIL, null),
				Arguments.of("Maria Silva", EMAIL, "curta"),
				Arguments.of("Maria Silva", EMAIL, "\uD83D\uDD12".repeat(11)),
				Arguments.of("Maria Silva", EMAIL, "é".repeat(37)),
				Arguments.of("Maria Silva", EMAIL, "Aa9!senha-com\ncontrole"));
	}

	private long quantidadeContas() {
		return jdbc.queryForObject("select count(*) from contas", Long.class);
	}

	private boolean bootstrapConcluido() {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"select bootstrap_concluido from controle_administracao where id = 1", Boolean.class));
	}
}
