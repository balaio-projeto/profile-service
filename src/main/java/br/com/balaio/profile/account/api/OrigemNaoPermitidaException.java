package br.com.balaio.profile.account.api;

public class OrigemNaoPermitidaException extends RuntimeException {
    public OrigemNaoPermitidaException() {
        super("Operação não permitida.");
    }
}
