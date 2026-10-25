package br.com.balaio.profile.account.application.port.out;

import java.util.Optional;
import java.util.UUID;

import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.Papel;

public interface ContasPort {
    Conta salvar(Conta conta);

    Optional<Conta> buscarPorId(UUID id);

    Optional<Conta> buscarPorEmail(Email email);

    long contarPorPapel(Papel papel);
}
