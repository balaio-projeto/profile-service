package br.com.balaio.profile.account.application.port.out;

import java.util.List;
import java.util.Set;

import br.com.balaio.profile.account.application.ContaAdministrativa;
import br.com.balaio.profile.account.domain.Papel;

public interface ListarContasAdminPort {
    long contar(Set<Papel> papeis, String padraoBusca);

    List<ContaAdministrativa> listar(Set<Papel> papeis, String padraoBusca, int deslocamento, int limite);
}
