package br.com.balaio.profile.account.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.balaio.profile.account.application.AlteracaoPapelException.Motivo;
import br.com.balaio.profile.account.application.port.in.AlterarPapelAdministrativoUseCase;
import br.com.balaio.profile.account.application.port.in.AutorizarOperacaoAdministrativaUseCase;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.application.port.out.ControleAdministracaoPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.UltimoAdministradorException;

@Service
public class AlterarPapelAdministrativoService implements AlterarPapelAdministrativoUseCase {
    private final ContasPort contas;
    private final ControleAdministracaoPort controle;
    private final AutorizarOperacaoAdministrativaUseCase autorizacao;

    public AlterarPapelAdministrativoService(ContasPort contas, ControleAdministracaoPort controle,
            AutorizarOperacaoAdministrativaUseCase autorizacao) {
        this.contas = contas;
        this.controle = controle;
        this.autorizacao = autorizacao;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void alterar(IdentidadeAutenticada solicitante, AlterarPapelComando comando) {
        if (solicitante == null) {
            throw new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO);
        }
        if (comando == null || comando.contaAlvoId() == null || comando.novoPapel() == null) {
            throw new AlteracaoPapelException(Motivo.COMANDO_INVALIDO);
        }
        try {
            // A ordem é global: singleton, solicitante, alvo, contagem e escrita.
            controle.bloquear();
            autorizacao.exigirPermissao(solicitante);
            Conta alvo = contas.bloquearPorId(comando.contaAlvoId())
                    .orElseThrow(() -> new AlteracaoPapelException(Motivo.CONTA_ALVO_INDISPONIVEL));
            if (alvo.papel() == comando.novoPapel()) {
                return;
            }
            long administradores = contas.contarPorPapel(Papel.ADMIN);
            Conta atualizada = alvo.alterarPapel(comando.novoPapel(), administradores);
            if (!contas.atualizarPapel(alvo.id(), alvo.papel(), atualizada.papel())) {
                throw new AlteracaoPapelException(Motivo.CONFLITO_ATUALIZACAO);
            }
        } catch (AcessoNegadoException | AlteracaoPapelException | UltimoAdministradorException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AlteracaoPapelException(Motivo.FALHA_ALTERACAO);
        }
    }
}
