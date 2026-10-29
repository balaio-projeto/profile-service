package br.com.balaio.profile.account.application.port.in;

import br.com.balaio.profile.account.application.ContaPublicaCadastrada;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;

public interface ConsultarUsuarioAtualUseCase {
    ContaPublicaCadastrada consultar(IdentidadeAutenticada identidade);
}
