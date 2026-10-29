package br.com.balaio.profile.account.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminInicialRunner implements ApplicationRunner {
    private final InicializarAdministradorUseCase inicializarAdministrador;

    public AdminInicialRunner(InicializarAdministradorUseCase inicializarAdministrador) {
        this.inicializarAdministrador = inicializarAdministrador;
    }

    @Override
    public void run(ApplicationArguments args) {
        inicializarAdministrador.inicializar();
    }
}
