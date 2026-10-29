package br.com.balaio.profile.account.application.port.out;

import br.com.balaio.profile.account.domain.ControleAdministracao;

public interface ControleAdministracaoPort {
    /**
     * Exige transacao de escrita existente. O bloqueio permanece ate seu termino.
     * As decisoes que dependem da quantidade de administradores devem consultar
     * as contas depois deste bloqueio, na mesma transacao READ COMMITTED.
     */
    ControleAdministracao bloquear();

    /**
     * Persiste somente a conclusao, sem permitir reabrir o bootstrap.
     * Deve participar da mesma transacao que criou o administrador inicial.
     */
    void concluirBootstrap();
}
