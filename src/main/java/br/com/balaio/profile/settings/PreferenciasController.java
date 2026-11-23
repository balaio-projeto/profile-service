package br.com.balaio.profile.settings;

import br.com.balaio.profile.account.api.CadastroPublicoResposta;
import br.com.balaio.profile.account.api.UsuarioPublicoResposta;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.account.imagem.ImagemContaService;
import br.com.balaio.profile.account.security.ValidarSessaoAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PreferenciasController {
    private final ValidarSessaoAdapter sessoes;
    private final EdicaoContaService contas;
    private final ConfiguracoesAtelieService configuracoes;
    private final ImagemContaService imagens;
    public PreferenciasController(ValidarSessaoAdapter sessoes, EdicaoContaService contas, ConfiguracoesAtelieService configuracoes,
            ImagemContaService imagens) {
        this.sessoes = sessoes; this.contas = contas; this.configuracoes = configuracoes; this.imagens = imagens;
    }
    @PatchMapping(value={"/usuario", "/conta/{id}"}, consumes="application/json", produces="application/json")
    public ResponseEntity<CadastroPublicoResposta<UsuarioPublicoResposta>> atualizarConta(
            @PathVariable(required=false) UUID id, @RequestBody Map<String,Object> campos, HttpServletRequest request) {
        var conta = contas.atualizar(identidade(request), id, campos);
        return resposta(UsuarioPublicoResposta.de(conta, imagens.imagemDe(conta.id())));
    }
    @GetMapping(value="/artesaos/{slug}/configuracoes", produces="application/json")
    public ResponseEntity<CadastroPublicoResposta<ConfiguracoesAtelieService.Configuracoes>> consultar(
            @PathVariable String slug, HttpServletRequest request) {
        return resposta(configuracoes.consultar(identidade(request), slug));
    }
    @PatchMapping(value="/artesaos/{slug}/configuracoes", consumes="application/json", produces="application/json")
    public ResponseEntity<CadastroPublicoResposta<ConfiguracoesAtelieService.Configuracoes>> atualizar(
            @PathVariable String slug, @RequestBody Map<String,Object> campos, HttpServletRequest request) {
        return resposta(configuracoes.atualizar(identidade(request), slug, campos));
    }
    private IdentidadeAutenticada identidade(HttpServletRequest request) {
        return sessoes.validarBearer(Collections.list(request.getHeaders("Authorization")));
    }
    private <T> ResponseEntity<CadastroPublicoResposta<T>> resposta(T dados) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new CadastroPublicoResposta<>(dados, null));
    }
}
