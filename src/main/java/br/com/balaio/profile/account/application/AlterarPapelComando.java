package br.com.balaio.profile.account.application;

import java.util.UUID;

import br.com.balaio.profile.account.domain.Papel;

public record AlterarPapelComando(UUID contaAlvoId, Papel novoPapel) {
}
