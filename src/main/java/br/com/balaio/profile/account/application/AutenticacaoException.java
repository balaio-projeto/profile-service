package br.com.balaio.profile.account.application;

public class AutenticacaoException extends RuntimeException {
    public AutenticacaoException() {
        super("Não foi possível concluir a autenticação.");
    }
}
