package br.com.balaio.profile.account.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.balaio.profile.account.application.BootstrapAdministradorException.Motivo;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.account.application.port.out.CodificarSenhaPort;
import br.com.balaio.profile.account.application.port.out.ConfiguracaoAdministradorInicialPort;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.application.port.out.ControleAdministracaoPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.PoliticaSenha;

@Service
public class InicializarAdministradorService implements InicializarAdministradorUseCase {
    private final ContasPort contas;
    private final ControleAdministracaoPort controle;
    private final ConfiguracaoAdministradorInicialPort configuracao;
    private final CodificarSenhaPort senhas;

    public InicializarAdministradorService(ContasPort contas, ControleAdministracaoPort controle,
            ConfiguracaoAdministradorInicialPort configuracao, CodificarSenhaPort senhas) {
        this.contas = contas;
        this.controle = controle;
        this.configuracao = configuracao;
        this.senhas = senhas;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void inicializar() {
        try {
            if (controle.bloquear().bootstrapConcluido()) {
                return;
            }

            ConfiguracaoAdministradorInicial parametros = configuracao.obter();
            if (parametros == null) {
                throw new BootstrapAdministradorException(Motivo.FALHA_INICIALIZACAO);
            }
            Email email = validarEmail(parametros.email());
            validarSenha(parametros.senhaInicial());

            if (contas.buscarPorEmail(email).isPresent()) {
                throw new BootstrapAdministradorException(Motivo.CONTA_EXISTENTE);
            }
            if (contas.contarPorPapel(Papel.ADMIN) > 0) {
                throw new BootstrapAdministradorException(Motivo.ADMIN_EXISTENTE);
            }

            var conta = new Conta(UUID.randomUUID(), "Administrador inicial", email,
                    senhas.codificar(parametros.senhaInicial()), Papel.ADMIN, true);
            contas.salvar(conta);
            controle.concluirBootstrap();
        } catch (BootstrapAdministradorException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BootstrapAdministradorException(Motivo.FALHA_INICIALIZACAO);
        }
    }

    private Email validarEmail(String valor) {
        if (valor == null || valor.isBlank()) {
            throw new BootstrapAdministradorException(Motivo.EMAIL_OBRIGATORIO);
        }
        try {
            return new Email(valor);
        } catch (IllegalArgumentException exception) {
            throw new BootstrapAdministradorException(Motivo.EMAIL_INVALIDO);
        }
    }

    private void validarSenha(String senha) {
        if (senha == null || senha.isBlank()) {
            throw new BootstrapAdministradorException(Motivo.SENHA_OBRIGATORIA);
        }
        try {
            PoliticaSenha.validar(senha);
        } catch (IllegalArgumentException exception) {
            throw new BootstrapAdministradorException(Motivo.SENHA_INVALIDA);
        }
    }
}
