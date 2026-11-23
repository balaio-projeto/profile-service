package br.com.balaio.profile.account.application.port.in;

import br.com.balaio.profile.account.application.AlterarPapelComando;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;

/**
 * Provisionamento controlado de organizadores de eventos (PI-210).
 * Aceita apenas as transições comprador → organizador e organizador → comprador.
 */
public interface ProvisionarOrganizadorUseCase {
    void alterar(IdentidadeAutenticada solicitante, AlterarPapelComando comando);
}
