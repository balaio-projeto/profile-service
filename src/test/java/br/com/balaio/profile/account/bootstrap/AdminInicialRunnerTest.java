package br.com.balaio.profile.account.bootstrap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;

class AdminInicialRunnerTest {
    private final DefaultApplicationArguments argumentos = new DefaultApplicationArguments(new String[0]);

    @Test
    void perfilLocalSemAdministradorNaoBloqueiaInicializacao() {
        var caso = mock(InicializarAdministradorUseCase.class);
        var ambiente = new MockEnvironment();
        ambiente.setActiveProfiles("local");
        new AdminInicialRunner(caso, ambiente).run(argumentos);
        verifyNoInteractions(caso);
    }

    @Test
    void demaisPerfisConservamBootstrap() {
        var caso = mock(InicializarAdministradorUseCase.class);
        new AdminInicialRunner(caso, new MockEnvironment()).run(argumentos);
        verify(caso).inicializar();
    }
}
