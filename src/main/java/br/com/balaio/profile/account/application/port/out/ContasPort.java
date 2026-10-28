package br.com.balaio.profile.account.application.port.out;

import java.util.Optional;
import java.util.UUID;

import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.SenhaProtegida;

public interface ContasPort {
    Conta salvar(Conta conta);

    Optional<Conta> buscarPorId(UUID id);

    Optional<Conta> bloquearPorId(UUID id);

    boolean atualizarSenha(UUID id, SenhaProtegida hashEsperado, SenhaProtegida novoHash);

    /** Exige transação de escrita e coordenação prévia pelo controle administrativo. */
    boolean atualizarPapel(UUID id, Papel papelEsperado, Papel novoPapel);

    Optional<Conta> buscarPorEmail(Email email);

    long contarPorPapel(Papel papel);
}
