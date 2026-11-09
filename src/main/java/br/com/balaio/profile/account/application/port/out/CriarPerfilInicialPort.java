package br.com.balaio.profile.account.application.port.out;

import br.com.balaio.profile.account.domain.Conta;

public interface CriarPerfilInicialPort {
    void criarParaConta(Conta conta);
}
