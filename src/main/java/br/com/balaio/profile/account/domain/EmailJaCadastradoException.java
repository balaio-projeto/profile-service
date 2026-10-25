package br.com.balaio.profile.account.domain;

public class EmailJaCadastradoException extends RuntimeException {
    public EmailJaCadastradoException() {
        super("E-mail já cadastrado.");
    }
}
