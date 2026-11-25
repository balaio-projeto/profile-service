package br.com.balaio.profile.account.application;

public class BuscaContaAdminException extends RuntimeException {
    public enum Motivo {
        EMAIL_INVALIDO("Informe um e-mail válido."),
        CONTA_NAO_ENCONTRADA("Nenhuma conta com esse e-mail."),
        FALHA_CONSULTA("Não foi possível consultar a conta."),
        FILTRO_INVALIDO("Filtros da listagem de contas inválidos.");

        private final String mensagem;

        Motivo(String mensagem) { this.mensagem = mensagem; }
    }

    private final Motivo motivo;

    public BuscaContaAdminException(Motivo motivo) {
        super(motivo.mensagem);
        this.motivo = motivo;
    }

    public Motivo motivo() { return motivo; }
}
