package br.com.balaio.profile.account.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import br.com.balaio.profile.account.application.BuscaContaAdminException.Motivo;
import br.com.balaio.profile.account.application.port.in.AutorizarOperacaoAdministrativaUseCase;
import br.com.balaio.profile.account.application.port.in.BuscarContaAdminUseCase;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;

@Service
public class BuscarContaAdminService implements BuscarContaAdminUseCase {
    private final ContasPort contas;
    private final AutorizarOperacaoAdministrativaUseCase autorizacao;

    public BuscarContaAdminService(ContasPort contas, AutorizarOperacaoAdministrativaUseCase autorizacao) {
        this.contas = contas;
        this.autorizacao = autorizacao;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContaAdministrativa buscarPorEmail(IdentidadeAutenticada solicitante, String email) {
        if (solicitante == null) {
            throw new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO);
        }
        autorizacao.exigirPermissao(solicitante);
        Email emailBuscado = emailValido(email);
        try {
            Conta conta = contas.buscarPorEmail(emailBuscado)
                    .orElseThrow(() -> new BuscaContaAdminException(Motivo.CONTA_NAO_ENCONTRADA));
            return new ContaAdministrativa(conta.id(), conta.nome(), conta.email().valor(), conta.papel());
        } catch (BuscaContaAdminException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BuscaContaAdminException(Motivo.FALHA_CONSULTA);
        }
    }

    private static Email emailValido(String email) {
        try {
            return new Email(email);
        } catch (IllegalArgumentException exception) {
            throw new BuscaContaAdminException(Motivo.EMAIL_INVALIDO);
        }
    }
}
