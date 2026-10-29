package br.com.balaio.profile.account.api;

public record CadastroPublicoResposta<T>(T dados, Erro erro) {
    public record Erro(String codigo, String mensagem) {
    }
}
