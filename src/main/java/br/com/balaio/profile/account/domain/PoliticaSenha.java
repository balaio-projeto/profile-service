package br.com.balaio.profile.account.domain;

import java.nio.charset.StandardCharsets;

public final class PoliticaSenha {
    private static final int MINIMO_CARACTERES = 12;
    private static final int MAXIMO_BYTES_UTF8 = 72;

    private PoliticaSenha() {
    }

    public static void validar(String senha) {
        if (senha == null || senha.isBlank()) {
            throw new IllegalArgumentException("Senha inválida.");
        }
        if (senha.codePointCount(0, senha.length()) < MINIMO_CARACTERES
                || !StandardCharsets.UTF_8.newEncoder().canEncode(senha)
                || senha.getBytes(StandardCharsets.UTF_8).length > MAXIMO_BYTES_UTF8
                || senha.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Senha inválida.");
        }
    }
}
