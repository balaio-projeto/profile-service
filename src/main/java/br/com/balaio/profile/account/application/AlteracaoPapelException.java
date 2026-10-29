package br.com.balaio.profile.account.application;

public class AlteracaoPapelException extends RuntimeException {
    public enum Motivo {
        COMANDO_INVALIDO("Dados da alteração de papel inválidos."),
        CONTA_ALVO_INDISPONIVEL("Conta alvo indisponível."),
        CONFLITO_ATUALIZACAO("A conta foi alterada durante a operação. Tente novamente."),
        FALHA_ALTERACAO("Não foi possível alterar o papel da conta.");

        private final String mensagem;

        Motivo(String mensagem) { this.mensagem = mensagem; }
    }

    private final Motivo motivo;

    public AlteracaoPapelException(Motivo motivo) {
        super(motivo.mensagem);
        this.motivo = motivo;
    }

    public Motivo motivo() { return motivo; }
}
