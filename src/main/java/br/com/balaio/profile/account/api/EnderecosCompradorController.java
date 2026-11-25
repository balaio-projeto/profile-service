package br.com.balaio.profile.account.api;

import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.security.ValidarSessaoAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/usuario/enderecos")
public class EnderecosCompradorController {
    public record Endereco(UUID id, String apelido, String rua, String numero, String complemento, String bairro,
            String cep, boolean principal) {}

    private final JdbcTemplate jdbc;
    private final ValidarSessaoAdapter sessoes;

    public EnderecosCompradorController(JdbcTemplate jdbc, ValidarSessaoAdapter sessoes) {
        this.jdbc = jdbc;
        this.sessoes = sessoes;
    }

    private UUID conta(HttpServletRequest request) {
        UUID id = sessoes.validarBearer(Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION))).contaId();
        String papel = jdbc.queryForObject("SELECT papel FROM contas WHERE id = ?", String.class, id);
        if (!Papel.podeComprar(papel))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Endereços de entrega disponíveis a quem compra no Balaio.");
        return id;
    }

    public List<Endereco> listar(UUID conta) {
        return jdbc.query("SELECT id, apelido, rua, numero, complemento, bairro, cep, principal FROM enderecos_comprador "
                        + "WHERE conta_id = ? ORDER BY principal DESC, criado_em, id",
                (rs, row) -> new Endereco(rs.getObject("id", UUID.class), rs.getString("apelido"),
                        rs.getString("rua"), rs.getString("numero"), rs.getString("complemento"),
                        rs.getString("bairro"), rs.getString("cep"), rs.getBoolean("principal")), conta);
    }

    private Endereco proprio(UUID conta, UUID id) {
        return listar(conta).stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Endereço não encontrado."));
    }

    private void bloquearConta(UUID conta) {
        jdbc.queryForObject("SELECT id FROM contas WHERE id = ? FOR UPDATE", UUID.class, conta);
    }

    private String campo(Object valor, String nome, int limite) {
        if (!(valor instanceof String texto) || texto.isBlank() || texto.length() > limite)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campo inválido: " + nome);
        return texto.trim();
    }

    private String opcional(Object valor, String nome, int limite) {
        if (valor == null) return null;
        if (!(valor instanceof String texto) || texto.trim().length() > limite)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campo inválido: " + nome);
        return texto.isBlank() ? null : texto.trim();
    }

    /** Convenção atual: o campo bairro carrega "bairro, cidade, UF", separados por vírgula. */
    private String bairro(Object valor) {
        String texto = campo(valor, "bairro", 255);
        long partes = java.util.Arrays.stream(texto.split(",")).filter(p -> !p.isBlank()).count();
        if (partes < 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Campo inválido: bairro (informe bairro, cidade e estado separados por vírgula)");
        return texto;
    }

    private String cep(Object valor) {
        String texto = campo(valor, "cep", 9);
        if (!texto.matches("[0-9]{5}-?[0-9]{3}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CEP inválido.");
        return texto;
    }

    @GetMapping
    public CadastroPublicoResposta<List<Endereco>> listar(HttpServletRequest request) {
        return new CadastroPublicoResposta<>(listar(conta(request)), null);
    }

    @GetMapping("/{id}")
    public CadastroPublicoResposta<Endereco> consultar(@PathVariable UUID id, HttpServletRequest request) {
        UUID conta = conta(request);
        return new CadastroPublicoResposta<>(proprio(conta, id), null);
    }

    @PostMapping
    @Transactional
    public CadastroPublicoResposta<Endereco> criar(@RequestBody Map<String, Object> dados, HttpServletRequest request) {
        UUID conta = conta(request);
        bloquearConta(conta);
        UUID id = UUID.randomUUID();
        boolean principal = listar(conta).isEmpty();
        jdbc.update("INSERT INTO enderecos_comprador(id, conta_id, apelido, rua, numero, complemento, bairro, cep, principal) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, conta, campo(dados.get("apelido"), "apelido", 80), campo(dados.get("rua"), "rua", 255),
                opcional(dados.get("numero"), "numero", 20), opcional(dados.get("complemento"), "complemento", 100),
                bairro(dados.get("bairro")), cep(dados.get("cep")), principal);
        return new CadastroPublicoResposta<>(proprio(conta, id), null);
    }

    @PatchMapping("/{id}")
    @Transactional
    public CadastroPublicoResposta<Endereco> atualizar(@PathVariable UUID id, @RequestBody Map<String, Object> dados,
            HttpServletRequest request) {
        UUID conta = conta(request);
        bloquearConta(conta);
        Endereco atual = proprio(conta, id);
        boolean principal = Boolean.TRUE.equals(dados.get("principal")) || atual.principal();
        if (principal) jdbc.update("UPDATE enderecos_comprador SET principal = false WHERE conta_id = ?", conta);
        jdbc.update("UPDATE enderecos_comprador SET apelido = ?, rua = ?, numero = ?, complemento = ?, bairro = ?, cep = ?, "
                        + "principal = ? WHERE id = ? AND conta_id = ?",
                campo(dados.getOrDefault("apelido", atual.apelido()), "apelido", 80),
                campo(dados.getOrDefault("rua", atual.rua()), "rua", 255),
                dados.containsKey("numero") ? opcional(dados.get("numero"), "numero", 20) : atual.numero(),
                dados.containsKey("complemento") ? opcional(dados.get("complemento"), "complemento", 100)
                        : atual.complemento(),
                dados.containsKey("bairro") ? bairro(dados.get("bairro")) : atual.bairro(),
                cep(dados.getOrDefault("cep", atual.cep())), principal, id, conta);
        return new CadastroPublicoResposta<>(proprio(conta, id), null);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public CadastroPublicoResposta<Endereco> excluir(@PathVariable UUID id, HttpServletRequest request) {
        UUID conta = conta(request);
        bloquearConta(conta);
        Endereco atual = proprio(conta, id);
        jdbc.update("DELETE FROM enderecos_comprador WHERE id = ? AND conta_id = ?", id, conta);
        if (atual.principal()) {
            var restantes = listar(conta);
            if (!restantes.isEmpty())
                jdbc.update("UPDATE enderecos_comprador SET principal = true WHERE id = ? AND conta_id = ?",
                        restantes.getFirst().id(), conta);
        }
        return new CadastroPublicoResposta<>(atual, null);
    }
}
