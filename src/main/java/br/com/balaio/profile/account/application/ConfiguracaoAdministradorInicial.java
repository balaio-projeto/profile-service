package br.com.balaio.profile.account.application;

public record ConfiguracaoAdministradorInicial(String email, String senhaInicial) {
    @Override
    public String toString() {
        return "ConfiguracaoAdministradorInicial[REDACTED]";
    }
}
