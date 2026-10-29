package br.com.balaio.profile.account.application;

public class BootstrapAdministradorException extends RuntimeException {
    public enum Motivo {
        EMAIL_OBRIGATORIO("ADMIN_EMAIL e obrigatorio enquanto o bootstrap estiver pendente."),
        EMAIL_INVALIDO("ADMIN_EMAIL possui formato invalido."),
        SENHA_OBRIGATORIA("ADMIN_SENHA_INICIAL e obrigatoria enquanto o bootstrap estiver pendente."),
        SENHA_INVALIDA("ADMIN_SENHA_INICIAL deve ter ao menos 12 caracteres, no maximo 72 bytes UTF-8 e nenhum caractere de controle."),
        CONTA_EXISTENTE("O e-mail configurado ja pertence a uma conta. E necessaria reconciliacao explicita."),
        ADMIN_EXISTENTE("Ja existe administrador com o bootstrap pendente. E necessaria reconciliacao explicita."),
        FALHA_INICIALIZACAO("Nao foi possivel concluir o bootstrap do administrador.");

        private final String mensagem;

        Motivo(String mensagem) {
            this.mensagem = mensagem;
        }
    }

    private final Motivo motivo;

    public BootstrapAdministradorException(Motivo motivo) {
        super(motivo.mensagem);
        this.motivo = motivo;
    }

    public Motivo motivo() {
        return motivo;
    }
}
