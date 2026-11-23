package br.com.balaio.profile.account.application.port.in;

import br.com.balaio.profile.account.application.ContaAdministrativa;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;

public interface BuscarContaAdminUseCase {
    ContaAdministrativa buscarPorEmail(IdentidadeAutenticada solicitante, String email);
}
