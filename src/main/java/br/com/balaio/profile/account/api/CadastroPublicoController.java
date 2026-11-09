package br.com.balaio.profile.account.api;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.profile.account.application.port.in.CadastrarContaPublicaUseCase;
import br.com.balaio.profile.account.security.CookieSessaoAdapter;
import br.com.balaio.profile.account.security.EmissorJwtAdapter;

@RestController
@RequestMapping("/api/v1/auth/cadastro")
public class CadastroPublicoController {
    private final CadastrarContaPublicaUseCase cadastro;
    private final EmissorJwtAdapter emissor;
    private final CookieSessaoAdapter cookies;

    public CadastroPublicoController(CadastrarContaPublicaUseCase cadastro, EmissorJwtAdapter emissor,
            CookieSessaoAdapter cookies) {
        this.cadastro = cadastro;
        this.emissor = emissor;
        this.cookies = cookies;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CadastroPublicoResposta<UsuarioPublicoResposta>> cadastrar(
            @RequestBody CadastroPublicoRequisicao requisicao, HttpServletRequest request) {
        
        var conta = cadastro.cadastrar(requisicao.paraComando());
        String token = emissor.emitir(conta);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE, cookies.criar(token, request.isSecure()).toString())
                .body(new CadastroPublicoResposta<>(UsuarioPublicoResposta.de(conta), null));
    }
}
