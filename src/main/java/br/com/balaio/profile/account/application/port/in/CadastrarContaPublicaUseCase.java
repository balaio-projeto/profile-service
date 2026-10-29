package br.com.balaio.profile.account.application.port.in;

import br.com.balaio.profile.account.application.CadastrarContaPublicaComando;
import br.com.balaio.profile.account.application.ContaPublicaCadastrada;

public interface CadastrarContaPublicaUseCase {
    ContaPublicaCadastrada cadastrar(CadastrarContaPublicaComando comando);
}
