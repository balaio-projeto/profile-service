package br.com.balaio.profile.account.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.balaio.profile.account.application.port.in.ConsultarUsuarioAtualUseCase;
import br.com.balaio.profile.account.application.port.out.ContasPort;

@Service
public class ConsultarUsuarioAtualService implements ConsultarUsuarioAtualUseCase {
    private final ContasPort contas;

    public ConsultarUsuarioAtualService(ContasPort contas) {
        this.contas = contas;
    }

    @Override
    @Transactional(readOnly = true)
    public ContaPublicaCadastrada consultar(IdentidadeAutenticada identidade) {
        if (identidade == null) {
            throw new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO);
        }
        var conta = contas.buscarPorId(identidade.contaId())
                .orElseThrow(() -> new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO));
        return new ContaPublicaCadastrada(conta.id(), conta.nome(), conta.email().valor(), conta.papel());
    }
}
