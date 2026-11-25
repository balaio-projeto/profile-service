package br.com.balaio.profile.account.api;

import java.util.Collections;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.profile.account.application.AlterarPapelComando;
import br.com.balaio.profile.account.application.ContaAdministrativa;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.account.application.PaginaContasAdmin;
import br.com.balaio.profile.account.application.port.in.BuscarContaAdminUseCase;
import br.com.balaio.profile.account.application.port.in.ListarContasAdminUseCase;
import br.com.balaio.profile.account.application.port.in.ProvisionarOrganizadorUseCase;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.imagem.ImagemContaService;
import br.com.balaio.profile.account.security.ValidarSessaoAdapter;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Atribuição e remoção do papel organizador por administrador (PI-210).
 * Não é uma API genérica de promoção: aceita somente organizador e comprador.
 */
@RestController
@RequestMapping("/api/v1/admin/contas")
public class OrganizadorAdminController {
    private final ProvisionarOrganizadorUseCase provisionar;
    private final BuscarContaAdminUseCase buscar;
    private final ListarContasAdminUseCase listar;
    private final ImagemContaService imagens;
    private final ValidarSessaoAdapter sessoes;

    public OrganizadorAdminController(ProvisionarOrganizadorUseCase provisionar, BuscarContaAdminUseCase buscar,
            ListarContasAdminUseCase listar, ImagemContaService imagens, ValidarSessaoAdapter sessoes) {
        this.provisionar = provisionar;
        this.buscar = buscar;
        this.listar = listar;
        this.imagens = imagens;
        this.sessoes = sessoes;
    }

    @GetMapping
    public ResponseEntity<?> buscarOuListar(
            @RequestParam(name = "email", required = false) String email,
            @RequestParam(name = "busca", required = false) String busca,
            @RequestParam(name = "papel", required = false) String papel,
            @RequestParam(name = "pagina", required = false) Integer pagina,
            @RequestParam(name = "tamanho", required = false) Integer tamanho, HttpServletRequest request) {
        IdentidadeAutenticada solicitante =
                sessoes.validarBearer(Collections.list(request.getHeaders("Authorization")));
        if (email == null) {
            PaginaContasAdmin contas = listar.listar(solicitante, busca, papel, pagina, tamanho);
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(RespostaPaginada.de(
                    contas.itens().stream().map(ContaAdminResposta::de).toList(), contas.janela()));
        }
        ContaAdministrativa encontrada = buscar.buscarPorEmail(solicitante, email);
        ContaAdminResposta conta = ContaAdminResposta.de(new ContaAdministrativa(encontrada.id(), encontrada.nome(),
                encontrada.email(), encontrada.papel(), imagemOuNulo(encontrada)));
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new CadastroPublicoResposta<>(conta, null));
    }

    @PatchMapping("/{id}/papel")
    public ResponseEntity<Void> alterarPapel(@PathVariable UUID id, @RequestBody AlterarPapelRequisicao requisicao,
            HttpServletRequest request) {
        IdentidadeAutenticada solicitante =
                sessoes.validarBearer(Collections.list(request.getHeaders("Authorization")));
        provisionar.alterar(solicitante, new AlterarPapelComando(id, papelSolicitado(requisicao)));
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    private String imagemOuNulo(ContaAdministrativa conta) {
        String imagem = imagens.imagemDe(conta.id());
        return imagem == null || imagem.isBlank() ? null : imagem;
    }

    private static Papel papelSolicitado(AlterarPapelRequisicao requisicao) {
        if (requisicao == null || requisicao.papel() == null) {
            throw new OrganizadorInvalidoException();
        }
        return switch (requisicao.papel()) {
            case "organizador" -> Papel.ORGANIZADOR;
            case "comprador" -> Papel.COMPRADOR;
            default -> throw new OrganizadorInvalidoException();
        };
    }

    static final class OrganizadorInvalidoException extends RuntimeException {
        OrganizadorInvalidoException() {
            super("Papel solicitado inválido.");
        }
    }
}
