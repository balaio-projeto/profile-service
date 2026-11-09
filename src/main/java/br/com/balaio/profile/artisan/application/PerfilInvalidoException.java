package br.com.balaio.profile.artisan.application;

import java.util.Map;

public class PerfilInvalidoException extends RuntimeException {
    private final Map<String, String> campos;
    public PerfilInvalidoException(Map<String, String> campos) {
        super("Dados do perfil inválidos.");
        this.campos = Map.copyOf(campos);
    }
    public Map<String, String> campos() { return campos; }
}
