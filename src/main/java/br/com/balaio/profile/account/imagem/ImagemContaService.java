package br.com.balaio.profile.account.imagem;

import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.artisan.media.MidiaPerfilService;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ImagemContaService {
    private final ContasPort contas;
    private final MidiaPerfilService midias;

    public ImagemContaService(ContasPort contas, MidiaPerfilService midias) {
        this.contas = contas;
        this.midias = midias;
    }

    public Conta exigirConta(IdentidadeAutenticada identidade) {
        if (identidade == null) {
            throw new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO);
        }
        return contas.buscarPorId(identidade.contaId())
                .orElseThrow(() -> new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO));
    }

    public void enviar(IdentidadeAutenticada identidade, byte[] arquivo) {
        var conta = exigirConta(identidade);
        if (conta.papel() == Papel.ARTESAO) {
            midias.armazenar(identidade, arquivo);
            midias.removerDaConta(conta.id());
            return;
        }
        midias.armazenarDaConta(conta.id(), arquivo);
    }

    public void remover(IdentidadeAutenticada identidade) {
        var conta = exigirConta(identidade);
        if (conta.papel() == Papel.ARTESAO) {
            midias.remover(identidade);
        }
        midias.removerDaConta(conta.id());
    }

    public String imagemDe(UUID contaId) {
        return contaId == null ? "" : midias.imagemDaConta(contaId);
    }
}
