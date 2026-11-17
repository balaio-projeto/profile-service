package br.com.balaio.profile.artisan.application;

import br.com.balaio.profile.artisan.domain.PerfilArtesao;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface PerfilArtesaoPort {
    List<PerfilArtesao> listar();
    Optional<PerfilArtesao> buscarPorSlug(String slug);
    Optional<PerfilArtesao> buscarPorContaId(UUID contaId);
    PerfilArtesao salvar(PerfilArtesao perfil);
}
