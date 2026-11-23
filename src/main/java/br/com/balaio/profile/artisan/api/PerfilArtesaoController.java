
package br.com.balaio.profile.artisan.api;

import br.com.balaio.profile.account.api.CadastroPublicoResposta;
import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.account.security.CookieSessaoAdapter;
import br.com.balaio.profile.account.security.ValidarSessaoAdapter;
import br.com.balaio.profile.artisan.application.PerfilArtesaoService;
import br.com.balaio.profile.artisan.media.MidiaPerfilService;
import br.com.balaio.profile.artisan.media.MidiaException;
import br.com.balaio.profile.artisan.media.ValidarImagem;
import java.io.IOException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class PerfilArtesaoController {

    private final PerfilArtesaoService service;
    private final ValidarSessaoAdapter sessoes;
    private final MidiaPerfilService midias;
    private final br.com.balaio.profile.curation.SelosRepository selos;

    public PerfilArtesaoController(
            PerfilArtesaoService service,
            ValidarSessaoAdapter sessoes,
            MidiaPerfilService midias,
            br.com.balaio.profile.curation.SelosRepository selos) {
        this.service = service;
        this.sessoes = sessoes;
        this.midias = midias;
        this.selos = selos;
    }

    @PutMapping(value = "/artesao/me/imagem", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CadastroPublicoResposta<PerfilArtesaoResponse>> enviarImagem(
            @RequestPart("arquivo") MultipartFile arquivo, HttpServletRequest request) {
        var dono = identidade(request);
        // Authorize before reading the bounded file into application memory.
        service.consultarMeu(dono);
        if (arquivo.getSize() > ValidarImagem.MAX_BYTES) {
            throw new MidiaException(MidiaException.Motivo.ARQUIVO_MUITO_GRANDE);
        }
        try {
            midias.armazenar(dono, arquivo.getBytes());
        } catch (IOException e) {
            throw new MidiaException(MidiaException.Motivo.ARQUIVO_INVALIDO);
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resposta(service.consultarMeu(dono)));
    }

    @DeleteMapping(value = "/artesao/me/imagem", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CadastroPublicoResposta<PerfilArtesaoResponse>> removerImagem(
            HttpServletRequest request) {
        var dono = identidade(request);
        midias.remover(dono);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resposta(service.consultarMeu(dono)));
    }

    @GetMapping(
            value = "/artesaos",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CadastroPublicoResposta<List<PerfilArtesaoResponse>> listar() {
        var verificados = selos.verificados();
        return new CadastroPublicoResposta<>(
                service.listar().stream()
                        .map(perfil -> PerfilArtesaoResponse.de(perfil, verificados.contains(perfil.id())))
                        .toList(),
                null);
    }

    @GetMapping(
            value = "/artesaos/{slug}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CadastroPublicoResposta<PerfilArtesaoResponse> publico(
            @PathVariable String slug) {
        return resposta(service.consultar(slug));
    }

    @GetMapping(
            value = "/artesao/me",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CadastroPublicoResposta<PerfilArtesaoResponse>> meu(
            HttpServletRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resposta(service.consultarMeu(identidade(request))));
    }

    @PostMapping(
            value = "/artesao/me",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CadastroPublicoResposta<PerfilArtesaoResponse>> criar(
            @Valid @RequestBody PerfilArtesaoRequest body,
            HttpServletRequest request) {
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resposta(service.criar(identidade(request), body)));
    }

    /** Contrato do painel: a identidade da sessao determina qual perfil editar. */
    @PatchMapping(
            value = "/artesao/me",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CadastroPublicoResposta<PerfilArtesaoResponse>> atualizarMeu(
            @RequestBody Map<String, Object> campos,
            HttpServletRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resposta(service.atualizarMeu(identidade(request), campos)));
    }

    @PatchMapping(
            value = "/artesaos/{slug}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CadastroPublicoResposta<PerfilArtesaoResponse>> atualizar(
            @PathVariable String slug,
            @RequestBody Map<String, Object> campos,
            HttpServletRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resposta(service.atualizarParcial(
                        identidade(request), slug, campos)));
    }

    private IdentidadeAutenticada identidade(HttpServletRequest request) {
        var autorizacoes = Collections.list(
                request.getHeaders(HttpHeaders.AUTHORIZATION));

        if (!autorizacoes.isEmpty()) {
            return sessoes.validarBearer(autorizacoes);
        }

        Cookie[] cookies = request.getCookies();

        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (CookieSessaoAdapter.NOME.equals(cookie.getName())
                        && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) {
                    return sessoes.validarBearer(
                            List.of("Bearer " + cookie.getValue()));
                }
            }
        }

        throw new AcessoNegadoException(
                AcessoNegadoException.Motivo.NAO_AUTENTICADO);
    }

    private CadastroPublicoResposta<PerfilArtesaoResponse> resposta(
            br.com.balaio.profile.artisan.domain.PerfilArtesao perfil) {
        return new CadastroPublicoResposta<>(
                PerfilArtesaoResponse.de(perfil, selos.verificado(perfil.id())), null);
    }
}
