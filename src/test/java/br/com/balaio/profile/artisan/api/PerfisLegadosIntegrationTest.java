package br.com.balaio.profile.artisan.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.artisan.application.PerfilArtesaoService;

@SpringBootTest
@Import(PostgreSQLTestConfiguration.class)
class PerfisLegadosIntegrationTest {
    @Autowired
    private PerfilArtesaoService perfis;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean(enforceOverride = true)
    private InicializarAdministradorUseCase inicializarAdministrador;

    @Test
    void perfisIniciaisCorrespondemAosSlugsDasPecasSemCriarContas() {
        Set<String> slugs = perfis.listar().stream().map(perfil -> perfil.slug()).collect(Collectors.toSet());

        assertThat(slugs).contains("mestre-nuca", "maria-de-caruaru", "mestre-dila",
                "mestre-amaro", "ze-do-cariri", "dona-bia-do-ipojuca",
                "mestre-vitalino-sucessores");
        assertThat(perfis.consultar("mestre-nuca").contaId()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from contas", Integer.class)).isZero();
    }
}
