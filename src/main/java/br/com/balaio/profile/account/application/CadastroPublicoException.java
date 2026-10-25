package br.com.balaio.profile.account.application;

public class CadastroPublicoException extends RuntimeException {
    public CadastroPublicoException() {
        super("Não foi possível concluir o cadastro público.");
    }
}
