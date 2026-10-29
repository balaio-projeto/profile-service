package br.com.balaio.profile.account.application;

import java.util.UUID;

public record IdentidadeAutenticada(UUID contaId) {
    public IdentidadeAutenticada {
        if (contaId == null) {
            throw new IllegalArgumentException("Identidade inválida.");
        }
    }
}
