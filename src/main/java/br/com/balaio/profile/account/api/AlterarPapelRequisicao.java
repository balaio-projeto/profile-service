package br.com.balaio.profile.account.api;

/** Corpo de {@code PATCH /api/v1/admin/contas/{id}/papel}. Valores aceitos: organizador ou comprador. */
public record AlterarPapelRequisicao(String papel) {
}
