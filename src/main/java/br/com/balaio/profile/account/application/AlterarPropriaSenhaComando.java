package br.com.balaio.profile.account.application;

public record AlterarPropriaSenhaComando(String senhaAtual, String novaSenha) {
    @Override
    public String toString() {
        return "AlterarPropriaSenhaComando[REDACTED]";
    }
}
