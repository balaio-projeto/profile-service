package br.com.balaio.profile.account.application;

import java.util.List;

public record PaginaContasAdmin(List<ContaAdministrativa> itens, JanelaPagina janela) {
}
