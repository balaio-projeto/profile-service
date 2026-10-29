package br.com.balaio.profile.account.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.balaio.profile.account.application.AlteracaoSenhaException.Motivo;
import br.com.balaio.profile.account.application.port.in.AlterarPropriaSenhaUseCase;
import br.com.balaio.profile.account.application.port.out.CodificarSenhaPort;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.application.port.out.VerificarSenhaPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.PoliticaSenha;

@Service
public class AlterarPropriaSenhaService implements AlterarPropriaSenhaUseCase {
    private final ContasPort contas;
    private final CodificarSenhaPort codificar;
    private final VerificarSenhaPort verificar;

    public AlterarPropriaSenhaService(ContasPort contas, CodificarSenhaPort codificar, VerificarSenhaPort verificar) {
        this.contas = contas;
        this.codificar = codificar;
        this.verificar = verificar;
    }

    @Override
    @Transactional
    public void alterar(IdentidadeAutenticada identidade, AlterarPropriaSenhaComando comando) {
        if (identidade == null) {
            throw new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO);
        }
        if (comando == null) {
            throw new AlteracaoSenhaException(Motivo.COMANDO_INVALIDO);
        }
        if (comando.senhaAtual() == null || comando.senhaAtual().isBlank()) {
            throw new AlteracaoSenhaException(Motivo.SENHA_ATUAL_OBRIGATORIA);
        }
        try {
            PoliticaSenha.validar(comando.novaSenha());
        } catch (IllegalArgumentException exception) {
            throw new AlteracaoSenhaException(Motivo.NOVA_SENHA_INVALIDA);
        }
        try {
            Conta conta = contas.buscarPorId(identidade.contaId())
                    .orElseThrow(() -> new AcessoNegadoException(AcessoNegadoException.Motivo.CONTA_INDISPONIVEL));
            if (!verificar.corresponde(comando.senhaAtual(), conta.senhaProtegida())) {
                throw new AlteracaoSenhaException(Motivo.SENHA_ATUAL_INCORRETA);
            }
            if (verificar.corresponde(comando.novaSenha(), conta.senhaProtegida())) {
                throw new AlteracaoSenhaException(Motivo.SENHA_REUTILIZADA);
            }
            Conta atualizada = conta.alterarSenha(codificar.codificar(comando.novaSenha()));
            if (!contas.atualizarSenha(conta.id(), conta.senhaProtegida(), atualizada.senhaProtegida())) {
                throw new AlteracaoSenhaException(Motivo.CONFLITO_ATUALIZACAO);
            }
        } catch (AlteracaoSenhaException | AcessoNegadoException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AlteracaoSenhaException(Motivo.FALHA_ALTERACAO);
        }
    }
}
