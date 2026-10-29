package br.com.balaio.profile.account.bootstrap;

import java.util.Objects;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import br.com.balaio.profile.account.application.ConfiguracaoAdministradorInicial;
import br.com.balaio.profile.account.application.port.out.ConfiguracaoAdministradorInicialPort;

@Component
public class ConfiguracaoAdministradorInicialEnvironmentAdapter implements ConfiguracaoAdministradorInicialPort {
    private final Function<String, String> variaveis;

    public ConfiguracaoAdministradorInicialEnvironmentAdapter() {
        this(System::getenv);
    }

    ConfiguracaoAdministradorInicialEnvironmentAdapter(Function<String, String> variaveis) {
        this.variaveis = Objects.requireNonNull(variaveis, "Leitor de variaveis obrigatorio.");
    }

    @Override
    public ConfiguracaoAdministradorInicial obter() {
        return new ConfiguracaoAdministradorInicial(
                variaveis.apply("ADMIN_EMAIL"),
                variaveis.apply("ADMIN_SENHA_INICIAL"));
    }
}
