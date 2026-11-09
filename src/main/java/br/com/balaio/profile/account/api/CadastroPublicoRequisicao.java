package br.com.balaio.profile.account.api;

import java.util.HashSet;
import java.util.Set;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.exc.MismatchedInputException;

import br.com.balaio.profile.account.application.CadastrarContaPublicaComando;
import br.com.balaio.profile.account.application.CadastroPublicoInvalidoException;

@JsonDeserialize(using = CadastroPublicoRequisicao.Desserializador.class)
public record CadastroPublicoRequisicao(String nome, String email, String senha, String perfil, String papel) {
    public CadastrarContaPublicaComando paraComando() {
        if (perfil != null && papel != null && !perfil.equals(papel)) {
            throw new CadastroPublicoInvalidoException();
        }
        return new CadastrarContaPublicaComando(nome, email, senha, perfil != null ? perfil : papel);
    }

    @Override
    public String toString() {
        return "CadastroPublicoRequisicao[REDACTED]";
    }

    public static final class Desserializador extends ValueDeserializer<CadastroPublicoRequisicao> {
        @Override
        public CadastroPublicoRequisicao deserialize(JsonParser parser, DeserializationContext contexto) {
            if (!parser.isExpectedStartObjectToken()) {
                throw entradaInvalida(parser);
            }
            Set<String> campos = new HashSet<>();
            String nome = null;
            String email = null;
            String senha = null;
            String perfil = null;
            String papel = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (!parser.hasToken(JsonToken.PROPERTY_NAME)) {
                    throw entradaInvalida(parser);
                }
                String campo = parser.currentName();
                if (!campos.add(campo)) {
                    throw entradaInvalida(parser);
                }
                parser.nextToken();
                switch (campo) {
                    case "nome" -> nome = lerTexto(parser);
                    case "email" -> email = lerTexto(parser);
                    case "senha" -> senha = lerTexto(parser);
                    case "perfil" -> perfil = lerTexto(parser);
                    case "papel" -> papel = lerTexto(parser);
                    default -> parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                throw entradaInvalida(parser);
            }
            return new CadastroPublicoRequisicao(nome, email, senha, perfil, papel);
        }

        private String lerTexto(JsonParser parser) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                throw entradaInvalida(parser);
            }
            return parser.getString();
        }

        private MismatchedInputException entradaInvalida(JsonParser parser) {
            return MismatchedInputException.from(parser, CadastroPublicoRequisicao.class,
                    "Dados de cadastro inválidos.");
        }
    }
}
