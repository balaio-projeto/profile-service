package br.com.balaio.profile.account.security;

import java.util.Objects;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import br.com.balaio.profile.account.application.port.out.CodificarSenhaPort;
import br.com.balaio.profile.account.domain.SenhaProtegida;

@Component
public class CodificarSenhaAdapter implements CodificarSenhaPort {
    private final PasswordEncoder passwordEncoder;

    public CodificarSenhaAdapter(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "Codificador obrigatorio.");
    }

    @Override
    public SenhaProtegida codificar(String senha) {
        try {
            return new SenhaProtegida(passwordEncoder.encode(senha));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Não foi possível proteger a senha.");
        }
    }
}
