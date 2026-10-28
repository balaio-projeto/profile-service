package br.com.balaio.profile.account.application.port.in;

import br.com.balaio.profile.account.application.AlterarPapelComando;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;

public interface AlterarPapelAdministrativoUseCase {
    void alterar(IdentidadeAutenticada solicitante, AlterarPapelComando comando);
}
