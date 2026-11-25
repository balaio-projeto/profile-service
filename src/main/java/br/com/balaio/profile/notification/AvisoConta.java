package br.com.balaio.profile.notification;

import java.util.UUID;

public record AvisoConta(UUID conta, Tipo tipo) {
    public enum Tipo { BOAS_VINDAS, SENHA_ALTERADA, SENHA_REDEFINIDA }
}
