package br.com.balaio.profile.account.application;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import br.com.balaio.profile.account.application.BuscaContaAdminException.Motivo;
import br.com.balaio.profile.account.application.port.in.AutorizarOperacaoAdministrativaUseCase;
import br.com.balaio.profile.account.application.port.in.ListarContasAdminUseCase;
import br.com.balaio.profile.account.application.port.out.ListarContasAdminPort;
import br.com.balaio.profile.account.domain.Papel;

@Service
public class ListarContasAdminService implements ListarContasAdminUseCase {
    private final ListarContasAdminPort contas;
    private final AutorizarOperacaoAdministrativaUseCase autorizacao;

    public ListarContasAdminService(ListarContasAdminPort contas, AutorizarOperacaoAdministrativaUseCase autorizacao) {
        this.contas = contas;
        this.autorizacao = autorizacao;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaginaContasAdmin listar(IdentidadeAutenticada solicitante, String busca, String papel, Integer pagina,
            Integer tamanho) {
        if (solicitante == null) {
            throw new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO);
        }
        autorizacao.exigirPermissao(solicitante);
        Set<Papel> papeis = papeisFiltrados(papel);
        if (!JanelaPagina.buscaValida(busca)) {
            throw new BuscaContaAdminException(Motivo.FILTRO_INVALIDO);
        }
        String padrao = JanelaPagina.padraoBusca(busca);
        var janela = JanelaPagina.de(pagina, tamanho, contas.contar(papeis, padrao));
        return new PaginaContasAdmin(contas.listar(papeis, padrao, janela.deslocamento(), janela.tamanho()), janela);
    }

    private static Set<Papel> papeisFiltrados(String papel) {
        String filtro = papel == null || papel.isBlank() ? "todos" : papel.strip();
        return switch (filtro) {
            case "todos" -> Set.of(Papel.COMPRADOR, Papel.ORGANIZADOR);
            case "comprador" -> Set.of(Papel.COMPRADOR);
            case "organizador" -> Set.of(Papel.ORGANIZADOR);
            default -> throw new BuscaContaAdminException(Motivo.FILTRO_INVALIDO);
        };
    }
}
