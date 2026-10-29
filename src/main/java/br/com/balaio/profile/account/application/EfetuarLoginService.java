package br.com.balaio.profile.account.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.balaio.profile.account.application.port.in.EfetuarLoginUseCase;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.application.port.out.VerificarSenhaPort;
import br.com.balaio.profile.account.domain.Email;

@Service
public class EfetuarLoginService implements EfetuarLoginUseCase {
    private final ContasPort contas;
    private final VerificarSenhaPort senhas;

    public EfetuarLoginService(ContasPort contas, VerificarSenhaPort senhas) {
        this.contas = contas;
        this.senhas = senhas;
    }

    @Override
    @Transactional(readOnly = true)
    public ContaPublicaCadastrada efetuar(EfetuarLoginComando comando) {
        if (comando == null || comando.senha() == null || comando.senha().isEmpty()) {
            throw new LoginInvalidoException();
        }
        Email email;
        try {
            email = new Email(comando.email());
        } catch (IllegalArgumentException exception) {
            throw new LoginInvalidoException();
        }
        try {
            var encontrada = contas.buscarPorEmail(email);
            if (encontrada.isEmpty()) {
                senhas.verificarContaAusente(comando.senha());
                throw new CredenciaisInvalidasException();
            }
            var conta = encontrada.orElseThrow();
            if (!senhas.corresponde(comando.senha(), conta.senhaProtegida())) {
                throw new CredenciaisInvalidasException();
            }
            return new ContaPublicaCadastrada(conta.id(), conta.nome(), conta.email().valor(), conta.papel());
        } catch (CredenciaisInvalidasException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AutenticacaoException();
        }
    }
}
