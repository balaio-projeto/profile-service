package br.com.balaio.profile.account.api;

import java.util.UUID;

import br.com.balaio.profile.account.application.ContaAdministrativa;

public record ContaAdminResposta(UUID id, String nome, String email, String papel) {
    static ContaAdminResposta de(ContaAdministrativa conta) {
        return new ContaAdminResposta(conta.id(), conta.nome(), conta.email(), conta.papel().codigo());
    }
}
