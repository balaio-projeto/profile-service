package br.com.balaio.profile.account.api;

import java.util.Collections;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.profile.account.application.port.in.ConsultarUsuarioAtualUseCase;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.security.ValidarSessaoAdapter;

@RestController
@RequestMapping("/api/v1/artesao/me")
public class ArtesaoAtualController {
    private final ValidarSessaoAdapter sessoes;
    private final ConsultarUsuarioAtualUseCase usuarios;

    public ArtesaoAtualController(ValidarSessaoAdapter sessoes, ConsultarUsuarioAtualUseCase usuarios) {
        this.sessoes = sessoes;
        this.usuarios = usuarios;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CadastroPublicoResposta<ArtesaoAtual>> consultar(HttpServletRequest request) {
        var identidade = sessoes.validarBearer(Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)));
        var conta = usuarios.consultar(identidade);
        if (conta.papel() != Papel.ARTESAO) {
            return ResponseEntity.status(403).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new CadastroPublicoResposta<>(null,
                            new CadastroPublicoResposta.Erro("SEM_PERMISSAO", "Operação permitida apenas a artesãos.")));
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CadastroPublicoResposta<>(
                        new ArtesaoAtual(conta.id().toString(), "artesao-" + conta.id()), null));
    }

    public record ArtesaoAtual(String id, String slug) {
    }
}
