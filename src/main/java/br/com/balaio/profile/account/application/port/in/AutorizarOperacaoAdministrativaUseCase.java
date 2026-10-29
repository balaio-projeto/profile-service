package br.com.balaio.profile.account.application.port.in;

import br.com.balaio.profile.account.application.IdentidadeAutenticada;

public interface AutorizarOperacaoAdministrativaUseCase {
    /** Participa da mesma transação de escrita da operação administrativa protegida. */
    void exigirPermissao(IdentidadeAutenticada identidade);
}
