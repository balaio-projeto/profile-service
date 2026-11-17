package br.com.balaio.profile.artisan.domain;

import java.util.UUID;

public record PerfilArtesao
        (UUID id,
         UUID contaId,
         String slug,
         String nome,
         String atelie,
         String historia,
         String territorio,
         String tecnica,
         String imagem) {
}
