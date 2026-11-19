package br.com.balaio.profile.artisan.media;

public class MidiaException extends RuntimeException {
    public enum Motivo { ARQUIVO_INVALIDO, ARQUIVO_MUITO_GRANDE, FORMATO_NAO_SUPORTADO,
        STORAGE_INDISPONIVEL, MIDIA_NAO_ENCONTRADA, CONFLITO }
    private final Motivo motivo;

    public MidiaException(Motivo motivo) {
        super(switch (motivo) {
            case ARQUIVO_INVALIDO -> "Envie JPEG, PNG ou WebP valido de ate 2 MiB.";
            case ARQUIVO_MUITO_GRANDE -> "A imagem deve ter no maximo 2 MiB.";
            case FORMATO_NAO_SUPORTADO -> "Envie uma imagem JPEG, PNG ou WebP.";
            case STORAGE_INDISPONIVEL -> "Armazenamento de imagens indisponivel.";
            case MIDIA_NAO_ENCONTRADA -> "Imagem nao encontrada.";
            case CONFLITO -> "A imagem mudou durante a operacao. Tente novamente.";
        });
        this.motivo = motivo;
    }
    public Motivo motivo() { return motivo; }
}
