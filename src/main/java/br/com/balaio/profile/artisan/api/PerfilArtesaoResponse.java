
package br.com.balaio.profile.artisan.api;

import br.com.balaio.profile.artisan.domain.PerfilArtesao;
import java.util.UUID;

public record PerfilArtesaoResponse(
        UUID id,
        String slug,
        String nome,
        String atelie,
        String historia,
        String territorio,
        String tecnica,
        String imagem,
        long obrasComercializadas,
        double avaliacaoMedia,
        boolean selo
) {
    public static PerfilArtesaoResponse de(PerfilArtesao perfil) {
        return new PerfilArtesaoResponse(
                perfil.id(),
                perfil.slug(),
                perfil.nome(),
                perfil.atelie(),
                perfil.historia(),
                perfil.territorio(),
                perfil.tecnica(),
                perfil.imagem(),
                0L,
                0.0,
                false
        );
    }
}
