package br.com.balaio.profile.account.application.port.in;

import br.com.balaio.profile.account.application.AlterarPropriaSenhaComando;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;

public interface AlterarPropriaSenhaUseCase {
    void alterar(IdentidadeAutenticada identidade, AlterarPropriaSenhaComando comando);
}
