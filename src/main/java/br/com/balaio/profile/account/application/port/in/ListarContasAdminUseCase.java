package br.com.balaio.profile.account.application.port.in;

import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.account.application.PaginaContasAdmin;

public interface ListarContasAdminUseCase {
    PaginaContasAdmin listar(IdentidadeAutenticada solicitante, String busca, String papel, Integer pagina,
            Integer tamanho);
}
