package br.com.balaio.profile.curation;

public class CuradoriaException extends RuntimeException {
    private final int status;
    private final String codigo;
    private final String campo;

    public CuradoriaException(int status, String codigo, String mensagem, String campo) {
        super(mensagem);
        this.status = status;
        this.codigo = codigo;
        this.campo = campo;
    }
    public int status() { return status; }
    public String codigo() { return codigo; }
    public String campo() { return campo; }
    static CuradoriaException invalido(String campo, String mensagem) {
        return new CuradoriaException(400, "REQUISICAO_INVALIDA", mensagem, campo);
    }
    static CuradoriaException conflito(String mensagem) {
        return new CuradoriaException(409, "CONFLITO_ESTADO", mensagem, null);
    }
    static CuradoriaException catalogo() {
        return new CuradoriaException(503, "CATALOGO_INDISPONIVEL", "Não foi possível conferir as peças publicadas. Tente novamente.", null);
    }
}
