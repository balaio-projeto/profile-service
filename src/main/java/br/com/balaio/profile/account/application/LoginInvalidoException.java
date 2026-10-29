package br.com.balaio.profile.account.application;

public class LoginInvalidoException extends IllegalArgumentException {
    public LoginInvalidoException() {
        super("Dados de autenticação inválidos.");
    }
}
