package br.com.balaio.profile.curation;

import br.com.balaio.profile.account.api.CadastroPublicoResposta;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.account.security.ValidarSessaoAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CuradoriaController {
    private final CuradoriaService service;
    private final ValidarSessaoAdapter sessoes;
    public CuradoriaController(CuradoriaService service,ValidarSessaoAdapter sessoes) { this.service=service; this.sessoes=sessoes; }
    @GetMapping(value="/artesao/selo", produces="application/json")
    public ResponseEntity<CadastroPublicoResposta<CuradoriaService.Estado>> consultar(HttpServletRequest request) {
        return resposta(service.consultar(identidade(request)));
    }
    @PostMapping(value="/artesao/selo", consumes="application/json", produces="application/json")
    public ResponseEntity<CadastroPublicoResposta<CuradoriaService.Estado>> solicitar(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        return resposta(service.solicitar(identidade(request),body));
    }
    @GetMapping(value="/admin/curadoria", produces="application/json")
    public ResponseEntity<CadastroPublicoResposta<List<CuradoriaService.Solicitacao>>> fila(HttpServletRequest request) {
        return resposta(service.fila(identidade(request)));
    }
    @PostMapping(value="/admin/curadoria/{id}/decisao", consumes="application/json", produces="application/json")
    public ResponseEntity<CadastroPublicoResposta<CuradoriaService.Solicitacao>> decidir(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        return resposta(service.decidir(identidade(request),id,body));
    }
    private IdentidadeAutenticada identidade(HttpServletRequest request) {
        return sessoes.validarBearer(Collections.list(request.getHeaders("Authorization")));
    }
    private <T> ResponseEntity<CadastroPublicoResposta<T>> resposta(T dados) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(new CadastroPublicoResposta<>(dados,null));
    }
}
