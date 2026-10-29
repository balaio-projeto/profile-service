package br.com.balaio.profile.account.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.balaio.profile.account.application.port.in.CadastrarContaPublicaUseCase;
import br.com.balaio.profile.account.application.port.out.CodificarSenhaPort;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.EmailJaCadastradoException;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.PoliticaSenha;

@Service
public class CadastrarContaPublicaService implements CadastrarContaPublicaUseCase {
    private final ContasPort contas;
    private final CodificarSenhaPort senhas;

    public CadastrarContaPublicaService(ContasPort contas, CodificarSenhaPort senhas) {
        this.contas = contas;
        this.senhas = senhas;
    }

    @Override
    @Transactional
    public ContaPublicaCadastrada cadastrar(CadastrarContaPublicaComando comando) {
        if (comando == null) {
            throw new IllegalArgumentException("Dados de cadastro inválidos.");
        }
        String nome = Conta.normalizarNome(comando.nome());
        Email email = new Email(comando.email());
        PoliticaSenha.validar(comando.senha());

        try {
            if (contas.buscarPorEmail(email).isPresent()) {
                throw new EmailJaCadastradoException();
            }

            var conta = new Conta(UUID.randomUUID(), nome, email,
                    senhas.codificar(comando.senha()), Papel.COMPRADOR, false);
            contas.salvar(conta);
            return new ContaPublicaCadastrada(conta.id(), Papel.COMPRADOR);
        } catch (EmailJaCadastradoException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new CadastroPublicoException();
        }
    }
}
