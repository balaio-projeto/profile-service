package br.com.balaio.profile.account.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.balaio.profile.account.application.AcessoNegadoException.Motivo;
import br.com.balaio.profile.account.application.port.in.AutorizarOperacaoAdministrativaUseCase;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Papel;

@Service
public class AutorizarOperacaoAdministrativaService implements AutorizarOperacaoAdministrativaUseCase {
    private final ContasPort contas;

    public AutorizarOperacaoAdministrativaService(ContasPort contas) { this.contas = contas; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void exigirPermissao(IdentidadeAutenticada identidade) {
        if (identidade == null) { throw new AcessoNegadoException(Motivo.NAO_AUTENTICADO); }
        try {
            Conta conta = contas.bloquearPorId(identidade.contaId())
                    .orElseThrow(() -> new AcessoNegadoException(Motivo.CONTA_INDISPONIVEL));
            if (conta.papel() != Papel.ADMIN) { throw new AcessoNegadoException(Motivo.PRIVILEGIO_INSUFICIENTE); }
            if (conta.trocaSenhaObrigatoria()) { throw new AcessoNegadoException(Motivo.TROCA_SENHA_OBRIGATORIA); }
        } catch (AcessoNegadoException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AcessoNegadoException(Motivo.FALHA_VERIFICACAO);
        }
    }
}
