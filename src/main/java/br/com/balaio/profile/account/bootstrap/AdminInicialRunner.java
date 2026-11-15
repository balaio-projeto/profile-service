package br.com.balaio.profile.account.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;

import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminInicialRunner implements ApplicationRunner {
    private final InicializarAdministradorUseCase inicializarAdministrador;
    private final Environment environment;

    public AdminInicialRunner(InicializarAdministradorUseCase inicializarAdministrador, Environment environment) {
        this.inicializarAdministrador = inicializarAdministrador;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (environment.matchesProfiles("local") && vazio(environment.getProperty("ADMIN_EMAIL"))
                && vazio(environment.getProperty("ADMIN_SENHA_INICIAL"))) return;
        inicializarAdministrador.inicializar();
    }

    private boolean vazio(String valor) {
        return valor == null || valor.isBlank();
    }
}
