package br.com.balaio.profile.artisan.media;

import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.artisan.application.PerfilArtesaoService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MidiaPerfilService {
    private static final Logger LOG = LoggerFactory.getLogger(MidiaPerfilService.class);
    private final PerfilArtesaoService perfis;
    private final MidiasPerfilPort midias;
    private final ArmazenamentoMidiaPort storage;
    private final ValidarImagem validar;

    public MidiaPerfilService(PerfilArtesaoService perfis, MidiasPerfilPort midias,
            ArmazenamentoMidiaPort storage, ValidarImagem validar) {
        this.perfis = perfis; this.midias = midias; this.storage = storage; this.validar = validar;
    }
    public MidiaPerfil armazenar(IdentidadeAutenticada identidade, byte[] arquivo) {
        var perfil = perfis.consultarMeu(identidade);
        if (arquivo == null) {
            throw new MidiaException(MidiaException.Motivo.ARQUIVO_INVALIDO);
        }
        if (arquivo.length > ValidarImagem.MAX_BYTES) {
            throw new MidiaException(MidiaException.Motivo.ARQUIVO_MUITO_GRANDE);
        }
        byte[] bytes = arquivo.clone();
        String tipo = validar.tipo(bytes);
        UUID id = UUID.randomUUID();
        var midia = new MidiaPerfil(id, perfil.id(), "profile/" + perfil.id() + "/" + id,
                tipo, bytes.length, hash(bytes));
        midias.reservar(midia);
        // PENDING survives upload failure, crash or uncertain commit. The worker
        // reconciles it after the grace period without delaying the upload response.
        storage.enviar(midia, bytes);
        midias.ativar(midia);
        return midia;
    }
    public byte[] recuperar(UUID id) {
        return recuperarConteudo(id).bytes();
    }
    public MidiaPerfil consultar(UUID id) {
        return midias.vinculada(id).orElseThrow(() -> new MidiaException(MidiaException.Motivo.MIDIA_NAO_ENCONTRADA));
    }
    public Conteudo recuperarConteudo(UUID id) {
        var m = consultar(id);
        byte[] bytes = storage.recuperar(m);
        if (bytes.length != m.tamanho() || !hash(bytes).equals(m.sha256())) {
            throw new MidiaException(MidiaException.Motivo.STORAGE_INDISPONIVEL);
        }
        return new Conteudo(m, bytes);
    }
    public record Conteudo(MidiaPerfil midia, byte[] bytes) { }
    public void remover(IdentidadeAutenticada identidade) {
        var perfil = perfis.consultarMeu(identidade);
        midias.remover(perfil.id());
    }
    @Scheduled(fixedDelayString = "${balaio.media.cleanup-delay:60000}", initialDelayString = "${balaio.media.cleanup-delay:60000}")
    public void limpar() {
        try {
            for (var m : midias.candidatasLimpeza()) {
                String etapa = "PREPARAR_EXCLUSAO";
                try {
                    if (midias.prepararExclusao(m)) {
                        etapa = "EXCLUIR_STORAGE";
                        storage.excluir(m);
                        etapa = "CONFIRMAR_EXCLUSAO";
                        midias.concluirExclusao(m.id());
                    }
                } catch (RuntimeException e) {
                    LOG.warn("Falha na limpeza de midia midiaId={} etapa={} categoria={}",
                            m.id(), etapa, categoria(e));
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Falha na limpeza de midia midiaId=indisponivel etapa=SELECIONAR_CANDIDATAS categoria={}",
                    categoria(e));
        }
    }
    private static String categoria(RuntimeException e) {
        if (e instanceof MidiaException midia) return midia.motivo().name();
        if (e instanceof DataAccessException) return "PERSISTENCIA";
        return "INESPERADA";
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 indisponivel."); }
    }
}
