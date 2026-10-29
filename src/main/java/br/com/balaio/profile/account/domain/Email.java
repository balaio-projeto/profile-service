package br.com.balaio.profile.account.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public record Email(String valor) {
    private static final int TAMANHO_MAXIMO = 254;
    private static final Pattern PARTE_LOCAL = Pattern.compile("[a-z0-9!#$%&'*+/=?^_\\x60{|}~.-]+");
    private static final Pattern ROTULO_DOMINIO = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    public Email {
        if (valor == null) {
            throw new IllegalArgumentException("E-mail inválido.");
        }
        valor = valor.strip().toLowerCase(Locale.ROOT);
        if (valor.isEmpty() || valor.length() > TAMANHO_MAXIMO || !formatoValido(valor)) {
            throw new IllegalArgumentException("E-mail inválido.");
        }
    }

    private static boolean formatoValido(String valor) {
        String[] partes = valor.split("@", -1);
        if (partes.length != 2 || !PARTE_LOCAL.matcher(partes[0]).matches()
                || partes[0].startsWith(".") || partes[0].endsWith(".") || partes[0].contains("..")) {
            return false;
        }
        String[] rotulos = partes[1].split("\\.", -1);
        if (rotulos.length < 2) {
            return false;
        }
        for (String rotulo : rotulos) {
            if (!ROTULO_DOMINIO.matcher(rotulo).matches()) {
                return false;
            }
        }
        return true;
    }
}
