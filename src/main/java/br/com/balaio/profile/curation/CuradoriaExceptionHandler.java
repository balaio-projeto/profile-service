package br.com.balaio.profile.curation;

import br.com.balaio.profile.account.api.OrigemNaoPermitidaException;
import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.artisan.application.PerfilNaoEncontradoException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=CuradoriaController.class)
public class CuradoriaExceptionHandler {
    public record Erro(String codigo,String mensagem,Map<String,String> campos) { }
    public record Resposta(Object dados,Erro erro) { }
    @ExceptionHandler(CuradoriaException.class)
    ResponseEntity<Resposta> dominio(CuradoriaException e) { return erro(e.status(),e.codigo(),e.getMessage(),e.campo()==null?null:Map.of(e.campo(),e.getMessage())); }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    ResponseEntity<Resposta> invalido() { return erro(400,"REQUISICAO_INVALIDA","Requisição inválida.",null); }
    @ExceptionHandler(AcessoNegadoException.class)
    ResponseEntity<Resposta> acesso(AcessoNegadoException e) {
        boolean semSessao=e.motivo()==AcessoNegadoException.Motivo.NAO_AUTENTICADO || e.motivo()==AcessoNegadoException.Motivo.CONTA_INDISPONIVEL;
        if (e.motivo()==AcessoNegadoException.Motivo.TROCA_SENHA_OBRIGATORIA) return erro(403,"TROCA_SENHA_OBRIGATORIA",e.getMessage(),null);
        return erro(semSessao?401:403,semSessao?"NAO_AUTENTICADO":"SEM_PERMISSAO","Operação não permitida.",null);
    }
    @ExceptionHandler(OrigemNaoPermitidaException.class)
    ResponseEntity<Resposta> origem() { return erro(403,"SEM_PERMISSAO","Operação não permitida.",null); }
    @ExceptionHandler(PerfilNaoEncontradoException.class)
    ResponseEntity<Resposta> perfil() { return erro(404,"RECURSO_NAO_ENCONTRADO","Perfil não encontrado.",null); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Resposta> inesperado() { return erro(503,"SERVICO_INDISPONIVEL","Serviço temporariamente indisponível.",null); }
    private ResponseEntity<Resposta> erro(int status,String codigo,String mensagem,Map<String,String> campos) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(new Resposta(null,new Erro(codigo,mensagem,campos)));
    }
}
