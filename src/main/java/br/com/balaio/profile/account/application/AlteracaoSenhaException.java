package br.com.balaio.profile.account.application;

public class AlteracaoSenhaException extends RuntimeException {
    public enum Motivo {
        SENHA_ATUAL_OBRIGATORIA("Informe a senha atual."),
        SENHA_ATUAL_INCORRETA("Senha atual incorreta."),
        NOVA_SENHA_INVALIDA("Nova senha inválida."),
        SENHA_REUTILIZADA("A nova senha deve ser diferente da senha atual."),
        COMANDO_INVALIDO("Dados da alteração de senha inválidos."),
        CONFLITO_ATUALIZACAO("A conta foi alterada durante a operação. Tente novamente."),
        FALHA_ALTERACAO("Não foi possível alterar a senha.");

        private final String mensagem;

        Motivo(String mensagem) { this.mensagem = mensagem; }
    }

    private final Motivo motivo;

    public AlteracaoSenhaException(Motivo motivo) {
        super(motivo.mensagem);
        this.motivo = motivo;
    }

    public Motivo motivo() { return motivo; }
}
