package br.com.balaio.profile.account.application;

public class AcessoNegadoException extends RuntimeException {
    public enum Motivo {
        NAO_AUTENTICADO("Autenticação necessária."),
        CONTA_INDISPONIVEL("Conta indisponível."),
        PRIVILEGIO_INSUFICIENTE("Permissão insuficiente."),
        TROCA_SENHA_OBRIGATORIA("Altere a senha inicial antes de executar operações administrativas."),
        FALHA_VERIFICACAO("Não foi possível verificar a permissão.");

        private final String mensagem;

        Motivo(String mensagem) { this.mensagem = mensagem; }
    }

    private final Motivo motivo;

    public AcessoNegadoException(Motivo motivo) {
        super(motivo.mensagem);
        this.motivo = motivo;
    }

    public Motivo motivo() { return motivo; }
}
