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
import br.com.balaio.profile.account.security.ValidarSessaoAdapter;

@RestController
@RequestMapping("/api/v1/usuario")
public class UsuarioAtualController {
    private final ValidarSessaoAdapter sessoes;
    private final ConsultarUsuarioAtualUseCase usuarios;

    public UsuarioAtualController(ValidarSessaoAdapter sessoes, ConsultarUsuarioAtualUseCase usuarios) {
        this.sessoes = sessoes;
        this.usuarios = usuarios;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CadastroPublicoResposta<UsuarioPublicoResposta>> consultar(HttpServletRequest request) {
        var identidade = sessoes.validarBearer(Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)));
        var conta = usuarios.consultar(identidade);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CadastroPublicoResposta<>(UsuarioPublicoResposta.de(conta), null));
    }
}
