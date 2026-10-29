package br.com.balaio.profile.account.application;

public class CadastroPublicoInvalidoException extends IllegalArgumentException {
    public CadastroPublicoInvalidoException() {
        super("Dados de cadastro inválidos.");
    }
}
