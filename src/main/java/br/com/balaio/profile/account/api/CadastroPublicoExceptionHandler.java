package br.com.balaio.profile.account.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import br.com.balaio.profile.account.application.CadastroPublicoException;
import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.application.CadastroPublicoInvalidoException;
import br.com.balaio.profile.account.application.AutenticacaoException;
import br.com.balaio.profile.account.application.CredenciaisInvalidasException;
import br.com.balaio.profile.account.application.LoginInvalidoException;
import br.com.balaio.profile.account.domain.EmailJaCadastradoException;
import br.com.balaio.profile.account.application.AlteracaoSenhaException;

@RestControllerAdvice(assignableTypes = { CadastroPublicoController.class, AutenticacaoController.class,
        UsuarioAtualController.class })
public class CadastroPublicoExceptionHandler {

    @ExceptionHandler(AcessoNegadoException.class)
    public ResponseEntity<CadastroPublicoResposta<Void>> acessoNegado(
            AcessoNegadoException exception) {

        boolean naoAutenticado =
                exception.motivo() == AcessoNegadoException.Motivo.NAO_AUTENTICADO
                        || exception.motivo() == AcessoNegadoException.Motivo.CONTA_INDISPONIVEL;

        HttpStatus status = naoAutenticado
                ? HttpStatus.UNAUTHORIZED
                : HttpStatus.FORBIDDEN;

        String codigo = naoAutenticado
                ? "NAO_AUTENTICADO"
                : "SEM_PERMISSAO";

        String mensagem = naoAutenticado
                ? "Autenticação necessária."
                : "Operação não permitida.";

        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(new CadastroPublicoResposta<>(
                        null,
                        new CadastroPublicoResposta.Erro(codigo, mensagem)
                ));
    }

    @ExceptionHandler({ CadastroPublicoInvalidoException.class, LoginInvalidoException.class,
            HttpMessageNotReadableException.class })
    public ResponseEntity<CadastroPublicoResposta<Void>> entradaInvalida() {
        return erro(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "Dados da requisição inválidos.");
    }

    @ExceptionHandler(EmailJaCadastradoException.class)
    public ResponseEntity<CadastroPublicoResposta<Void>> emailEmUso() {
        return erro(HttpStatus.CONFLICT, "EMAIL_EM_USO", "E-mail já cadastrado.");
    }

    @ExceptionHandler(CadastroPublicoException.class)
    public ResponseEntity<CadastroPublicoResposta<Void>> falhaCadastro() {
        return erro(HttpStatus.INTERNAL_SERVER_ERROR, "CADASTRO_INDISPONIVEL",
                new CadastroPublicoException().getMessage());
    }

    @ExceptionHandler(CredenciaisInvalidasException.class)
    public ResponseEntity<CadastroPublicoResposta<Void>> credenciaisInvalidas() {
        return erro(HttpStatus.UNAUTHORIZED, "CREDENCIAIS_INVALIDAS", "E-mail ou senha inválidos.");
    }

    @ExceptionHandler(OrigemNaoPermitidaException.class)
    public ResponseEntity<CadastroPublicoResposta<Void>> origemNaoPermitida() {
        return erro(HttpStatus.FORBIDDEN, "SEM_PERMISSAO", "Operação não permitida.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CadastroPublicoResposta<Void>> falhaInesperada() {
        return erro(HttpStatus.INTERNAL_SERVER_ERROR, "AUTENTICACAO_INDISPONIVEL",
                new AutenticacaoException().getMessage());
    }

    @ExceptionHandler(AlteracaoSenhaException.class)
    public ResponseEntity<CadastroPublicoResposta<Void>> erroAlteracaoSenha(
            AlteracaoSenhaException exception) {

        if (exception.motivo()
                == AlteracaoSenhaException.Motivo.CONFLITO_ATUALIZACAO) {
            return erro(
                    HttpStatus.CONFLICT,
                    "CONFLITO_ATUALIZACAO",
                    exception.getMessage()
            );
        }

        return erro(
                HttpStatus.BAD_REQUEST,
                "ALTERACAO_SENHA_INVALIDA",
                exception.getMessage()
        );
    }

    private ResponseEntity<CadastroPublicoResposta<Void>> erro(HttpStatus status, String codigo, String mensagem) {
        return ResponseEntity.status(status)
                .body(new CadastroPublicoResposta<>(null, new CadastroPublicoResposta.Erro(codigo, mensagem)));
    }
}
