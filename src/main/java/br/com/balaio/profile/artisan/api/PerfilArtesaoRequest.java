package br.com.balaio.profile.artisan.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** DTO para a criacao explicita, caso a rota temporaria POST /artesao/me seja mantida. */
public record PerfilArtesaoRequest(
        @NotBlank @Size(max = 240) String nome,
        @NotBlank @Size(max = 240) String atelie,
        @Size(max = 2000) String historia,
        @Size(max = 120) String territorio,
        @Size(max = 120) String tecnica,
        @Size(max = 1024)
        @Pattern(regexp = "^(?!data:)[^\\r\\n]*$", message = "Informe uma referencia de midia, nao uma Data URL")
        String imagem) { }
