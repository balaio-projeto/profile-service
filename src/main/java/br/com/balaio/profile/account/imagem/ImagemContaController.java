package br.com.balaio.profile.account.imagem;

import br.com.balaio.profile.account.api.CadastroPublicoResposta;
import br.com.balaio.profile.account.api.UsuarioPublicoResposta;
import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.account.application.port.in.ConsultarUsuarioAtualUseCase;
import br.com.balaio.profile.account.security.CookieSessaoAdapter;
import br.com.balaio.profile.account.security.ValidarSessaoAdapter;
import br.com.balaio.profile.artisan.media.MidiaException;
import br.com.balaio.profile.artisan.media.ValidarImagem;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/usuario/imagem")
public class ImagemContaController {
    public static final String CAMINHO = "/api/v1/usuario/imagem";

    private final ValidarSessaoAdapter sessoes;
    private final ImagemContaService imagens;
    private final ConsultarUsuarioAtualUseCase usuarios;

    public ImagemContaController(ValidarSessaoAdapter sessoes, ImagemContaService imagens,
            ConsultarUsuarioAtualUseCase usuarios) {
        this.sessoes = sessoes;
        this.imagens = imagens;
        this.usuarios = usuarios;
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CadastroPublicoResposta<UsuarioPublicoResposta>> enviar(
            @RequestPart("arquivo") MultipartFile arquivo, HttpServletRequest request) {
        var identidade = identidade(request);
        imagens.exigirConta(identidade);
        if (arquivo.getSize() > ValidarImagem.MAX_BYTES) {
            throw new MidiaException(MidiaException.Motivo.ARQUIVO_MUITO_GRANDE);
        }
        try {
            imagens.enviar(identidade, arquivo.getBytes());
        } catch (IOException e) {
            throw new MidiaException(MidiaException.Motivo.ARQUIVO_INVALIDO);
        }
        return resposta(identidade);
    }

    @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CadastroPublicoResposta<UsuarioPublicoResposta>> remover(HttpServletRequest request) {
        var identidade = identidade(request);
        imagens.remover(identidade);
        return resposta(identidade);
    }

    private ResponseEntity<CadastroPublicoResposta<UsuarioPublicoResposta>> resposta(IdentidadeAutenticada identidade) {
        var conta = usuarios.consultar(identidade);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CadastroPublicoResposta<>(UsuarioPublicoResposta.de(conta, imagens.imagemDe(conta.id())), null));
    }

    private IdentidadeAutenticada identidade(HttpServletRequest request) {
        var autorizacoes = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (!autorizacoes.isEmpty()) {
            return sessoes.validarBearer(autorizacoes);
        }
        var cookies = request.getCookies();
        if (cookies != null) {
            for (var cookie : cookies) {
                if (CookieSessaoAdapter.NOME.equals(cookie.getName()) && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) {
                    return sessoes.validarBearer(List.of("Bearer " + cookie.getValue()));
                }
            }
        }
        throw new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO);
    }
}
