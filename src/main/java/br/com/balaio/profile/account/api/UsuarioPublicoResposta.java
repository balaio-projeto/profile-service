package br.com.balaio.profile.account.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import br.com.balaio.profile.account.application.ContaPublicaCadastrada;
import br.com.balaio.profile.account.domain.Papel;

public record UsuarioPublicoResposta(String id, String nome, String email, String papel,
        @JsonInclude(JsonInclude.Include.NON_NULL) String perfil, String imagem) {
    public static UsuarioPublicoResposta de(ContaPublicaCadastrada conta) {
        return de(conta, "");
    }

    public static UsuarioPublicoResposta de(ContaPublicaCadastrada conta, String imagem) {
        boolean possuiPerfil = conta.papel() == Papel.COMPRADOR || conta.papel() == Papel.ARTESAO;
        return new UsuarioPublicoResposta(conta.id().toString(), conta.nome(), conta.email(),
                conta.papel().codigo(), possuiPerfil ? conta.papel().codigo() : null, imagem == null ? "" : imagem);
    }
}
