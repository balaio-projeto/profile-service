package br.com.balaio.profile.account.api;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.profile.account.application.port.in.EfetuarLoginUseCase;
import br.com.balaio.profile.account.security.CookieSessaoAdapter;
import br.com.balaio.profile.account.security.EmissorJwtAdapter;

@RestController
@RequestMapping("/api/v1/auth")
public class AutenticacaoController {
    private final EfetuarLoginUseCase login;
    private final EmissorJwtAdapter emissor;
    private final CookieSessaoAdapter cookies;

    public AutenticacaoController(EfetuarLoginUseCase login, EmissorJwtAdapter emissor, CookieSessaoAdapter cookies) {
        this.login = login;
        this.emissor = emissor;
        this.cookies = cookies;
    }

    @PostMapping(value = { "/login", "/entrar" }, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CadastroPublicoResposta<UsuarioPublicoResposta>> entrar(
            @RequestBody LoginRequisicao requisicao, HttpServletRequest request) {
        var conta = login.efetuar(requisicao.paraComando());
        String token = emissor.emitir(conta);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE, cookies.criar(token, request.isSecure()).toString())
                .body(new CadastroPublicoResposta<>(UsuarioPublicoResposta.de(conta), null));
    }

    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CadastroPublicoResposta<SessaoEncerrada>> sair(HttpServletRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE, cookies.limpar(request.isSecure()).toString())
                .body(new CadastroPublicoResposta<>(new SessaoEncerrada(true), null));
    }

    public record SessaoEncerrada(boolean encerrada) {
    }
}
