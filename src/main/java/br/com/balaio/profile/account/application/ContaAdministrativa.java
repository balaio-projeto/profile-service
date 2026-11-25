package br.com.balaio.profile.account.application;

import java.util.UUID;

import br.com.balaio.profile.account.domain.Papel;

public record ContaAdministrativa(UUID id, String nome, String email, Papel papel, String imagem) {
    public ContaAdministrativa(UUID id, String nome, String email, Papel papel) {
        this(id, nome, email, papel, null);
    }
}
