package br.com.balaio.profile.artisan.media;

import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

@Component
public class S3MidiaAdapter implements ArmazenamentoMidiaPort {
    private final ObjectProvider<S3Client> clients;
    private final String bucket;

    public S3MidiaAdapter(ObjectProvider<S3Client> clients, @Value("${MEDIA_S3_BUCKET:balaio-media}") String bucket) {
        this.clients = clients;
        this.bucket = bucket;
    }
    private S3Client client() {
        var client = clients.getIfAvailable();
        if (client == null) throw indisponivel();
        return client;
    }
    public void enviar(MidiaPerfil m, byte[] bytes) {
        try {
            client().putObject(b -> b.bucket(bucket).key(m.chave()).contentType(m.tipo())
                    .metadata(java.util.Map.of("sha256", m.sha256())), RequestBody.fromBytes(bytes));
        } catch (SdkException e) { throw indisponivel(); }
    }
    public byte[] recuperar(MidiaPerfil m) {
        try (var input = client().getObject(b -> b.bucket(bucket).key(m.chave()))) {
            return input.readNBytes(ValidarImagem.MAX_BYTES + 1);
        } catch (SdkException | IOException e) { throw indisponivel(); }
    }
    public void excluir(MidiaPerfil m) {
        try { client().deleteObject(b -> b.bucket(bucket).key(m.chave())); }
        catch (SdkException e) { throw indisponivel(); }
    }
    private MidiaException indisponivel() { return new MidiaException(MidiaException.Motivo.STORAGE_INDISPONIVEL); }
}
