package br.com.balaio.profile.recovery;

public class RecoveryPasswordException extends RuntimeException {
    public RecoveryPasswordException() { super("Senha inválida."); }
}
