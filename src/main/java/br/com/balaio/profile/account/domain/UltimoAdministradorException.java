package br.com.balaio.profile.account.domain;

public class UltimoAdministradorException extends RuntimeException {
    public UltimoAdministradorException() {
        super("O último administrador não pode perder o papel administrativo.");
    }
}
