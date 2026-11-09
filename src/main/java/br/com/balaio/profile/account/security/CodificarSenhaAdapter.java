package br.com.balaio.profile.account.security;

import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import jakarta.annotation.PostConstruct;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import br.com.balaio.profile.account.application.port.out.CodificarSenhaPort;
import br.com.balaio.profile.account.application.port.out.VerificarSenhaPort;
import br.com.balaio.profile.account.domain.SenhaProtegida;

@Component
public class CodificarSenhaAdapter implements CodificarSenhaPort, VerificarSenhaPort {
    private final PasswordEncoder passwordEncoder;
    private SenhaProtegida senhaFicticia;

    public CodificarSenhaAdapter(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "Codificador obrigatorio.");
    }

    @PostConstruct
    void prepararVerificacaoFicticia() {
        senhaFicticia = codificar(UUID.randomUUID().toString());
    }

    @Override
    public void verificarContaAusente(String senha) {
        if (senhaFicticia == null) {
            throw new IllegalStateException("Verificação de credenciais indisponível.");
        }
        corresponde(senha, senhaFicticia);
    }

    @Override
    public SenhaProtegida codificar(String senha) {
        try {
            return new SenhaProtegida(passwordEncoder.encode(senha));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Não foi possível proteger a senha.");
        }
    }

    @Override
    public boolean corresponde(String senha, SenhaProtegida senhaProtegida) {
        if (senha == null || !StandardCharsets.UTF_8.newEncoder().canEncode(senha)
                || senha.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        try {
            return passwordEncoder.matches(senha, senhaProtegida.valor());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Não foi possível conferir a senha.");
        }
    }
}
