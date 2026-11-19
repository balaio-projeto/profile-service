package br.com.balaio.profile.artisan.api;

import br.com.balaio.profile.artisan.media.MidiaPerfil;
import br.com.balaio.profile.artisan.media.MidiaPerfilService;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/arquivos/perfis")
public class MidiaPerfilController {
    private final MidiaPerfilService midias;

    public MidiaPerfilController(MidiaPerfilService midias) {
        this.midias = midias;
    }

    @GetMapping("/{midiaId}")
    public ResponseEntity<byte[]> recuperar(@PathVariable UUID midiaId) {
        var conteudo = midias.recuperarConteudo(midiaId);
        return cabecalhos(conteudo.midia()).body(conteudo.bytes());
    }

    @RequestMapping(value = "/{midiaId}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> consultar(@PathVariable UUID midiaId) {
        return cabecalhos(midias.consultar(midiaId)).build();
    }

    private ResponseEntity.BodyBuilder cabecalhos(MidiaPerfil midia) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(midia.tipo()))
                .contentLength(midia.tamanho())
                .header("X-Content-Type-Options", "nosniff")
                // Revalidate the association on every read, including after removal.
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
