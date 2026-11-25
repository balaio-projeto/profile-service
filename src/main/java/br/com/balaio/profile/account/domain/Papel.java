package br.com.balaio.profile.account.domain;

public enum Papel {
    COMPRADOR("comprador"),
    ARTESAO("artesao"),
    ADMIN("admin"),
    ORGANIZADOR("organizador");

    private final String codigo;

    Papel(String codigo) {
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }

    public static boolean podeComprar(String codigo) {
        return COMPRADOR.codigo.equals(codigo) || ARTESAO.codigo.equals(codigo);
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
