package br.com.balaio.profile.account.application;

public record CadastrarContaPublicaComando(String nome, String email, String senha, String papelSolicitado) {
    public CadastrarContaPublicaComando(String nome, String email, String senha) {
        this(nome, email, senha, null);
    }

    @Override
    public String toString() {
        return "CadastrarContaPublicaComando[REDACTED]";
    }
}
