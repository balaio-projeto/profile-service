package br.com.balaio.profile.account.api;

import java.util.List;

import br.com.balaio.profile.account.application.JanelaPagina;

public record RespostaPaginada<T>(List<T> dados, CadastroPublicoResposta.Erro erro, Paginacao paginacao) {
    public record Paginacao(int pagina, int tamanho, long total, int totalPaginas) {
    }

    public static <T> RespostaPaginada<T> de(List<T> dados, JanelaPagina janela) {
        return new RespostaPaginada<>(dados, null,
                new Paginacao(janela.pagina(), janela.tamanho(), janela.total(), janela.totalPaginas()));
    }
}
