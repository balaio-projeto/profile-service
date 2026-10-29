package br.com.balaio.profile;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgreSQLTestConfiguration.class)
class ProfileServiceApplicationTests {

	@MockitoBean(enforceOverride = true)
	private InicializarAdministradorUseCase inicializarAdministrador;

	@Autowired
	private Flyway flyway;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void aplicaMigrationsSemCriarAdministrador() {
		assertThat(flyway.info().applied()).hasSize(2);
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from contas", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from controle_administracao", Long.class)).isEqualTo(1L);
		assertThat(jdbc.queryForObject(
				"select bootstrap_concluido from controle_administracao where id = 1", Boolean.class)).isFalse();
	}

}
