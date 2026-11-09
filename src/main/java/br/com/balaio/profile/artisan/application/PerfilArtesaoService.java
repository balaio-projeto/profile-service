
package br.com.balaio.profile.artisan.application;

import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.application.AcessoNegadoException.Motivo;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.application.port.out.CriarPerfilInicialPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.artisan.api.PerfilArtesaoRequest;
import br.com.balaio.profile.artisan.domain.PerfilArtesao;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PerfilArtesaoService implements CriarPerfilInicialPort {

    private final PerfilArtesaoPort perfis;
    private final ContasPort contas;
    private final Validator validator;

    public PerfilArtesaoService(
            PerfilArtesaoPort perfis,
            ContasPort contas,
            Validator validator) {
        this.perfis = perfis;
        this.contas = contas;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public List<PerfilArtesao> listar() {
        return perfis.listar();
    }

    @Transactional(readOnly = true)
    public PerfilArtesao consultar(String slug) {
        return perfis.buscarPorSlug(slug)
                .orElseThrow(PerfilNaoEncontradoException::new);
    }

    @Transactional(readOnly = true)
    public PerfilArtesao consultarMeu(IdentidadeAutenticada identidade) {
        var conta = autenticar(identidade);
        exigirArtesao(conta.papel());

        return perfis.buscarPorContaId(conta.id())
                .orElseThrow(PerfilNaoEncontradoException::new);
    }

    /** Cria o perfil publico na mesma transacao do cadastro da conta artesã. */
    @Override
    @Transactional
    public void criarParaConta(Conta conta) {
        if (conta == null) {
            throw new IllegalArgumentException("Conta obrigatoria.");
        }

        exigirArtesao(conta.papel());

        if (perfis.buscarPorContaId(conta.id()).isPresent()) {
            throw new PerfilDuplicadoException();
        }

        String nome = conta.nome();
        String prefixo = "Ateliê de ";

        String atelie = prefixo + nome.substring(
                0, Math.min(nome.length(), 240 - prefixo.length()));

        perfis.salvar(new PerfilArtesao(
                UUID.randomUUID(),
                conta.id(),
                slug(nome, conta.id()),
                nome,
                atelie,
                "",
                "",
                "",
                imagemOuPadrao(null)
        ));
    }

    /** Criação do perfil: somente um perfil por conta de artesão. */
    @Transactional
    public PerfilArtesao criar(
            IdentidadeAutenticada identidade,
            PerfilArtesaoRequest request) {

        var conta = autenticar(identidade);
        exigirArtesao(conta.papel());

        if (perfis.buscarPorContaId(conta.id()).isPresent()) {
            throw new PerfilDuplicadoException();
        }

        String slug = slug(conta.nome(), conta.id());

        PerfilArtesao novo = new PerfilArtesao(
                UUID.randomUUID(),
                conta.id(),
                slug,
                request.nome(),
                request.atelie(),
                opcional(request.historia()),
                opcional(request.territorio()),
                opcional(request.tecnica()),
                imagemOuPadrao(request.imagem())
        );

        return perfis.salvar(novo);
    }

    /** Atualiza exclusivamente o perfil vinculado a identidade autenticada. */
    @Transactional
    public PerfilArtesao atualizarMeu(
            IdentidadeAutenticada identidade,
            Map<String, Object> campos) {

        PerfilArtesao meu = consultarMeu(identidade);
        return atualizarParcial(identidade, meu.slug(), campos);
    }

    /** PATCH do contrato público: campos omitidos não são substituídos. */
    @Transactional
    public PerfilArtesao atualizarParcial(
            IdentidadeAutenticada identidade,
            String slug,
            Map<String, Object> campos) {

        var conta = autenticar(identidade);
        PerfilArtesao atual = consultar(slug);

        boolean dono = conta.id().equals(atual.contaId())
                && conta.papel() == Papel.ARTESAO;

        boolean admin = conta.papel() == Papel.ADMIN
                && !conta.trocaSenhaObrigatoria();

        if (!dono && !admin) {
            throw new AcessoNegadoException(
                    Motivo.PRIVILEGIO_INSUFICIENTE);
        }

        if (campos == null || campos.isEmpty()) {
            throw new PerfilInvalidoException(
                    Map.of("corpo", "Informe ao menos um campo."));
        }

        Map<String, String> erros = new LinkedHashMap<>();

        for (var campo : campos.entrySet()) {
            if (!java.util.Set.of(
                    "nome", "atelie", "historia",
                    "territorio", "tecnica", "imagem"
            ).contains(campo.getKey())) {
                erros.put(campo.getKey(), "Campo não permitido.");
            } else if (campo.getValue() != null
                    && !(campo.getValue() instanceof String)) {
                erros.put(campo.getKey(), "Deve ser texto.");
            }
        }

        if (!erros.isEmpty()) {
            throw new PerfilInvalidoException(erros);
        }

        var candidato = new PerfilArtesaoRequest(
                campo(campos, "nome", atual.nome()),
                campo(campos, "atelie", atual.atelie()),
                campo(campos, "historia", atual.historia()),
                campo(campos, "territorio", atual.territorio()),
                campo(campos, "tecnica", atual.tecnica()),
                campo(campos, "imagem", atual.imagem())
        );

        validator.validate(candidato).forEach(v ->
                erros.putIfAbsent(
                        v.getPropertyPath().toString(),
                        v.getMessage())
        );

        if (candidato.nome() == null || candidato.nome().isBlank()) {
            erros.put("nome", "Nome obrigatorio.");
        }

        if (candidato.atelie() == null || candidato.atelie().isBlank()) {
            erros.put("atelie", "Atelie obrigatorio.");
        }

        if (candidato.historia() == null
                || candidato.territorio() == null
                || candidato.tecnica() == null
                || candidato.imagem() == null) {
            erros.put("corpo", "Campos nao podem ser nulos.");
        }

        if (candidato.imagem() != null
                && candidato.imagem().startsWith("data:")) {
            erros.put("imagem", "Envie uma referencia de midia.");
        }

        if (!erros.isEmpty()) {
            throw new PerfilInvalidoException(erros);
        }

        return perfis.salvar(new PerfilArtesao(
                atual.id(),
                atual.contaId(),
                atual.slug(),
                candidato.nome(),
                candidato.atelie(),
                candidato.historia(),
                candidato.territorio(),
                candidato.tecnica(),
                candidato.imagem()
        ));
    }

    private String opcional(String valor) {
        return valor == null ? "" : valor;
    }

    private String imagemOuPadrao(String valor) {
        return valor == null || valor.isBlank()
                ? "/fotos/ImagemBase.webp"
                : valor;
    }

    private String campo(
            Map<String, Object> campos,
            String nome,
            String anterior) {
        return campos.containsKey(nome)
                ? (String) campos.get(nome)
                : anterior;
    }

    private Conta autenticar(IdentidadeAutenticada identidade) {
        if (identidade == null) {
            throw new AcessoNegadoException(Motivo.NAO_AUTENTICADO);
        }

        return contas.buscarPorId(identidade.contaId())
                .orElseThrow(() -> new AcessoNegadoException(
                        Motivo.NAO_AUTENTICADO));
    }

    private void exigirArtesao(Papel papel) {
        if (papel != Papel.ARTESAO) {
            throw new AcessoNegadoException(
                    Motivo.PRIVILEGIO_INSUFICIENTE);
        }
    }

    private String slug(String nome, UUID id) {
        String base = Normalizer.normalize(
                        nome, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");

        if (base.isBlank()) {
            base = "artesao";
        }

        return base.substring(
                        0, Math.min(70, base.length()))
                .replaceAll("-$", "")
                + "-" + id.toString().substring(0, 8);
    }
}
