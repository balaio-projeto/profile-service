package br.com.balaio.profile.curation;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PecasPublicadasClient {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String base;

    public PecasPublicadasClient(@Value("${balaio.catalog.url}") String base) {
        this.base = base.replaceAll("/+$", "");
    }

    /** Consulta pública, sem JWT: o contrato autenticado do catálogo pode incluir rascunhos. */
    public int contar(String slug) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var path = URLEncoder.encode(slug, StandardCharsets.UTF_8).replace("+", "%20");
            var request = HttpRequest.newBuilder(URI.create(base + "/api/v1/artesaos/" + path + "/pecas"))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build();
            pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
            // Inclui a leitura do corpo: um upstream que envia só cabeçalhos não prende a transação.
            var response = pending.get(5, TimeUnit.SECONDS);
                if (response.statusCode() != 200) throw CuradoriaException.catalogo();
                byte[] bytes = response.body();
                if (bytes.length > MAX_BYTES) throw CuradoriaException.catalogo();
                var envelope = json.readTree(bytes);
                var dados = envelope.get("dados");
                if (dados == null || !dados.isArray() || !envelope.has("erro") || !envelope.get("erro").isNull()) {
                    throw CuradoriaException.catalogo();
                }
                int count = 0;
                for (var peca : dados) {
                    if (!peca.isObject() || !peca.has("situacao") || !peca.has("artesao")) throw CuradoriaException.catalogo();
                    if (slug.equals(peca.get("artesao").asText()) && "publicada".equals(peca.get("situacao").asText())
                            && (!peca.has("inativadoEm") || peca.get("inativadoEm").isNull())) count++;
                }
                return count;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CuradoriaException.catalogo();
        } catch (Exception e) {
            throw CuradoriaException.catalogo();
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }
}
