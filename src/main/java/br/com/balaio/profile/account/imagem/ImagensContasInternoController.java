package br.com.balaio.profile.account.imagem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.profile.account.api.CadastroPublicoResposta;

@RestController
public class ImagensContasInternoController {
    public record Consulta(List<UUID> contas) { }

    static final int LIMITE_POR_CONSULTA = 100;

    private final ImagemContaService imagens;
    private final String tokenInterno;

    public ImagensContasInternoController(ImagemContaService imagens,
            @Value("${balaio.internal-token:}") String tokenInterno) {
        this.imagens = imagens;
        this.tokenInterno = tokenInterno;
    }

    @PostMapping("/internal/v1/contas/imagens")
    public ResponseEntity<CadastroPublicoResposta<Map<UUID, String>>> imagens(@RequestBody(required = false) Consulta consulta,
            @RequestHeader(value = "X-Balaio-Service-Token", required = false) String token) {
        if (!tokenValido(token))
            return erro(HttpStatus.FORBIDDEN, "SEM_PERMISSAO", "Operação interna não permitida.");
        if (consulta == null || consulta.contas() == null || consulta.contas().size() > LIMITE_POR_CONSULTA
                || consulta.contas().stream().anyMatch(java.util.Objects::isNull))
            return erro(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "Informe até " + LIMITE_POR_CONSULTA + " contas.");
        var resultado = new LinkedHashMap<UUID, String>();
        for (UUID conta : consulta.contas().stream().distinct().toList()) {
            String imagem = imagens.imagemDe(conta);
            if (imagem != null && !imagem.isBlank()) resultado.put(conta, imagem);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CadastroPublicoResposta<>(resultado, null));
    }

    private boolean tokenValido(String recebido) {
        return tokenInterno.length() >= 32 && recebido != null && MessageDigest.isEqual(
                tokenInterno.getBytes(StandardCharsets.UTF_8), recebido.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<CadastroPublicoResposta<Map<UUID, String>>> erro(HttpStatus status, String codigo,
            String mensagem) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new CadastroPublicoResposta<>(null, new CadastroPublicoResposta.Erro(codigo, mensagem)));
    }
}
