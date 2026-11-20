package br.com.balaio.profile.settings;

import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.application.ContaPublicaCadastrada;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.EmailJaCadastradoException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EdicaoContaService {
    private final ContasPort contas;
    public EdicaoContaService(ContasPort contas) { this.contas = contas; }

    @Transactional
    public ContaPublicaCadastrada atualizar(IdentidadeAutenticada identidade, UUID alvo, Map<String, Object> campos) {
        if (identidade == null) throw new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO);
        if (alvo != null && !alvo.equals(identidade.contaId()))
            throw new AcessoNegadoException(AcessoNegadoException.Motivo.PRIVILEGIO_INSUFICIENTE);
        validarCampos(campos, Set.of("nome", "email"));
        var atual = contas.bloquearPorId(identidade.contaId())
                .orElseThrow(() -> new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO));
        String nome = atual.nome();
        Email email = atual.email();
        if (campos.containsKey("nome")) {
            try { nome = Conta.normalizarNome(texto(campos, "nome")); }
            catch (IllegalArgumentException e) { throw new CamposInvalidosException("nome", "Informe um nome de até 240 caracteres."); }
        }
        if (campos.containsKey("email")) {
            try { email = new Email(texto(campos, "email")); }
            catch (IllegalArgumentException e) { throw new CamposInvalidosException("email", "Informe um e-mail válido."); }
        }
        var existente = contas.buscarPorEmail(email);
        if (existente.isPresent() && !existente.get().id().equals(atual.id())) throw new EmailJaCadastradoException();
        var salva = contas.salvar(new Conta(atual.id(), nome, email, atual.senhaProtegida(), atual.papel(), atual.trocaSenhaObrigatoria()));
        return new ContaPublicaCadastrada(salva.id(), salva.nome(), salva.email().valor(), salva.papel());
    }

    static void validarCampos(Map<String, Object> campos, Set<String> permitidos) {
        if (campos == null || campos.isEmpty()) throw new CamposInvalidosException("corpo", "Informe ao menos um campo.");
        for (var campo : campos.keySet()) if (!permitidos.contains(campo))
            throw new CamposInvalidosException(campo, "Campo não permitido.");
    }
    static String texto(Map<String, Object> campos, String campo) {
        if (!(campos.get(campo) instanceof String valor) || valor.isBlank() || valor.chars().anyMatch(Character::isISOControl))
            throw new CamposInvalidosException(campo, "Informe um texto válido.");
        return valor.strip();
    }
}
