package br.com.balaio.profile.artisan.media;

import java.util.UUID;

public record MidiaPerfil(UUID id, UUID perfilId, String chave, String tipo, int tamanho, String sha256) {
    public static final String PREFIXO_PUBLICO = "/api/v1/arquivos/perfis/";
    public static final String PLACEHOLDER = "/fotos/ImagemBase.webp";

    public String url() { return PREFIXO_PUBLICO + id; }
}
