package br.com.balaio.profile.account.application.port.out;

import br.com.balaio.profile.account.domain.SenhaProtegida;

public interface VerificarSenhaPort {
    boolean corresponde(String senha, SenhaProtegida senhaProtegida);
}
