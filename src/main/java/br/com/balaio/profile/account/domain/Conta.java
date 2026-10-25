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
}
