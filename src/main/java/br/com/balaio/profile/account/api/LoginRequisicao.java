package br.com.balaio.profile.account.api;

import java.util.HashSet;
import java.util.Set;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.exc.MismatchedInputException;

import br.com.balaio.profile.account.application.EfetuarLoginComando;

@JsonDeserialize(using = LoginRequisicao.Desserializador.class)
public record LoginRequisicao(String email, String senha) {
    public EfetuarLoginComando paraComando() {
        return new EfetuarLoginComando(email, senha);
    }

    @Override
    public String toString() {
        return "LoginRequisicao[REDACTED]";
    }

    public static final class Desserializador extends ValueDeserializer<LoginRequisicao> {
        @Override
        public LoginRequisicao deserialize(JsonParser parser, DeserializationContext contexto) {
            if (!parser.isExpectedStartObjectToken()) {
                throw entradaInvalida(parser);
            }
            Set<String> campos = new HashSet<>();
            String email = null;
            String senha = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (!parser.hasToken(JsonToken.PROPERTY_NAME) || !campos.add(parser.currentName())) {
                    throw entradaInvalida(parser);
                }
                String campo = parser.currentName();
                parser.nextToken();
                switch (campo) {
                    case "email" -> email = lerTexto(parser);
                    case "senha" -> senha = lerTexto(parser);
                    default -> parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                throw entradaInvalida(parser);
            }
            return new LoginRequisicao(email, senha);
        }

        private String lerTexto(JsonParser parser) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                throw entradaInvalida(parser);
            }
            return parser.getString();
        }

        private MismatchedInputException entradaInvalida(JsonParser parser) {
            return MismatchedInputException.from(parser, LoginRequisicao.class,
                    "Dados de autenticação inválidos.");
        }
    }
}
