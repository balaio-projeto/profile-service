package br.com.balaio.profile.account.api;

import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Cookie;

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

import br.com.balaio.profile.account.application.AlterarPropriaSenhaComando;
import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.application.port.in.AlterarPropriaSenhaUseCase;
import br.com.balaio.profile.account.security.ValidarSessaoAdapter;

@RestController
@RequestMapping("/api/v1/auth")
public class AutenticacaoController {
    private final EfetuarLoginUseCase login;
    private final EmissorJwtAdapter emissor;
    private final CookieSessaoAdapter cookies;
    private final AlterarPropriaSenhaUseCase alterarSenha;
    private final ValidarSessaoAdapter sessoes;

    public AutenticacaoController(
            EfetuarLoginUseCase login,
            EmissorJwtAdapter emissor,
            CookieSessaoAdapter cookies,
            AlterarPropriaSenhaUseCase alterarSenha,
            ValidarSessaoAdapter sessoes) {

        this.login = login;
        this.emissor = emissor;
        this.cookies = cookies;
        this.alterarSenha = alterarSenha;
        this.sessoes = sessoes;
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

    @PostMapping(
            value = "/alterar-senha",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CadastroPublicoResposta<SenhaAlteradaResposta>> alterarSenha(
            @RequestBody AlterarSenhaRequisicao requisicao,
            HttpServletRequest request) {

        var identidade = identificarUsuario(request);

        alterarSenha.alterar(
                identidade,
                new AlterarPropriaSenhaComando(
                        requisicao.senhaAtual(),
                        requisicao.novaSenha()
                )
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CadastroPublicoResposta<>(
                        new SenhaAlteradaResposta(true), null
                ));
    }

    private br.com.balaio.profile.account.application.IdentidadeAutenticada
    identificarUsuario(HttpServletRequest request) {

        var autorizacoes = Collections.list(
                request.getHeaders(HttpHeaders.AUTHORIZATION)
        );

        if (!autorizacoes.isEmpty()) {
            return sessoes.validarBearer(autorizacoes);
        }

        Cookie[] cookiesRecebidos = request.getCookies();

        if (cookiesRecebidos != null) {
            for (Cookie cookie : cookiesRecebidos) {
                if (CookieSessaoAdapter.NOME.equals(cookie.getName())
                        && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) {

                    return sessoes.validarBearer(
                            List.of("Bearer " + cookie.getValue())
                    );
                }
            }
        }

        throw new AcessoNegadoException(
                AcessoNegadoException.Motivo.NAO_AUTENTICADO
        );
    }

    public record AlterarSenhaRequisicao(
            String senhaAtual,
            String novaSenha) {

        @Override
        public String toString() {
            return "AlterarSenhaRequisicao[REDACTED]";
        }
    }

    public record SenhaAlteradaResposta(boolean alterada) {
    }

    public record SessaoEncerrada(boolean encerrada) {
    }
}
