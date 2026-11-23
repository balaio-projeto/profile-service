package br.com.balaio.profile.artisan.media;

import java.util.UUID;

public record MidiaPerfil(UUID id, UUID perfilId, UUID contaId, String chave, String tipo, int tamanho, String sha256) {
    public static final String PREFIXO_PUBLICO = "/api/v1/arquivos/perfis/";
    public static final String PLACEHOLDER = "/fotos/ImagemBase.webp";

    public MidiaPerfil(UUID id, UUID perfilId, String chave, String tipo, int tamanho, String sha256) {
        this(id, perfilId, null, chave, tipo, tamanho, sha256);
    }

    public static MidiaPerfil daConta(UUID id, UUID contaId, String chave, String tipo, int tamanho, String sha256) {
        return new MidiaPerfil(id, null, contaId, chave, tipo, tamanho, sha256);
    }

    public boolean pertenceAConta() { return perfilId == null; }

    public String url() { return PREFIXO_PUBLICO + id; }
}
