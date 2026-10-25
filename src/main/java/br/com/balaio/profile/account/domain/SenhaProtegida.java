package br.com.balaio.profile.account.domain;

import java.util.regex.Pattern;

public record SenhaProtegida(String valor) {
    private static final Pattern FORMATO = Pattern.compile(
            "\\{bcrypt\\}\\$2[aby]\\$(?:0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}");

    public SenhaProtegida {
        if (valor == null || !FORMATO.matcher(valor).matches()) {
            throw new IllegalArgumentException("Senha protegida inválida.");
        }
    }

    @Override
    public String toString() {
        return "SenhaProtegida[valor=REDACTED]";
    }
}
