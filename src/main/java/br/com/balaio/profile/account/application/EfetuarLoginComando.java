package br.com.balaio.profile.account.application;

public record EfetuarLoginComando(String email, String senha) {
    @Override
    public String toString() {
        return "EfetuarLoginComando[REDACTED]";
    }
}
