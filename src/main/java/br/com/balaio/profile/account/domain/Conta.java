package br.com.balaio.profile.account.domain;

import java.util.UUID;

public record Conta(
        UUID id,
        String nome,
        Email email,
        SenhaProtegida senhaProtegida,
        Papel papel,
        boolean trocaSenhaObrigatoria) {

    public Conta {
        if (id == null || email == null || senhaProtegida == null || papel == null) {
            throw new IllegalArgumentException("Dados da conta inválidos.");
        }
        nome = normalizarNome(nome);
    }

    public static String normalizarNome(String nome) {
        if (nome == null) {
            throw new IllegalArgumentException("Nome inválido.");
        }
        String normalizado = nome.strip();
        if (normalizado.isEmpty() || normalizado.length() > 240) {
            throw new IllegalArgumentException("Nome inválido.");
        }
        return normalizado;
    }

    public Conta alterarSenha(SenhaProtegida novaSenha) {
        if (novaSenha == null || novaSenha.equals(senhaProtegida)) {
            throw new IllegalArgumentException("A alteração exige uma nova senha protegida.");
        }
        return new Conta(id, nome, email, novaSenha, papel, false);
    }

    public Conta alterarPapel(Papel novoPapel, long totalAdministradores) {
        if (novoPapel == null || totalAdministradores < 0) {
            throw new IllegalArgumentException("Dados da alteração de papel inválidos.");
        }
        if (papel == novoPapel) {
            return this;
        }
        if (papel == Papel.ADMIN && novoPapel != Papel.ADMIN && totalAdministradores <= 1) {
            throw new UltimoAdministradorException();
        }
        return new Conta(id, nome, email, senhaProtegida, novoPapel, trocaSenhaObrigatoria);
    }
}
