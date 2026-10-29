package br.com.balaio.profile.account.application.port.in;

import br.com.balaio.profile.account.application.ContaPublicaCadastrada;
import br.com.balaio.profile.account.application.EfetuarLoginComando;

public interface EfetuarLoginUseCase {
    ContaPublicaCadastrada efetuar(EfetuarLoginComando comando);
}
