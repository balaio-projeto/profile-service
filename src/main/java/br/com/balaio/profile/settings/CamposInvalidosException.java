package br.com.balaio.profile.settings;

import java.util.Map;

final class CamposInvalidosException extends RuntimeException {
    private final Map<String, String> campos;
    CamposInvalidosException(String campo, String mensagem) {
        super("Campos inválidos.");
        campos = Map.of(campo, mensagem);
    }
    Map<String, String> campos() { return campos; }
}
