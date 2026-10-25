package br.com.balaio.profile.account.domain;

public enum Papel {
    COMPRADOR("comprador"),
    ARTESAO("artesao"),
    ADMIN("admin");

    private final String codigo;

    Papel(String codigo) {
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }

    public static Papel fromCodigo(String codigo) {
        for (Papel papel : values()) {
            if (papel.codigo.equals(codigo)) {
                return papel;
            }
        }
        throw new IllegalArgumentException("Papel inválido.");
    }
}
