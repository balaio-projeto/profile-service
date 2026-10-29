# profile-service

Contas, perfis de compradores e artesãos, selo e curadoria. O serviço é independente, usa Java 25 e Spring Boot 4.1.1 e recebe requisições externas por meio do api-gateway.

## Stack inicial

- Spring MVC, Spring Data JPA e PostgreSQL
- Flyway para migrações do banco próprio
- Bean Validation
- Spring Boot Actuator

## Execução

Porta HTTP padrão: 8082. Para executar pela IDE, defina `PROFILE_SERVICE_PORT` (opcional), `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD`, além da configuração de autenticação descrita abaixo. O banco e suas credenciais são configurados no repositório `platform`.

No Windows: `.\mvnw.cmd spring-boot:run`. Testes: `.\mvnw.cmd test`.

O `Dockerfile` constrói uma imagem própria. Em `platform`, use `docker compose up --build -d profile-service` para iniciar este serviço e seu banco ou `docker compose --profile extended up --build -d` para iniciar todos. Health: `http://localhost:8082/actuator/health`.

As migrations V1 e V2 já criam contas e o registro singleton de controle administrativo. O Flyway aplica o schema e o Hibernate o valida. Cadastro, login/alias e logout estão disponíveis sob `/api/v1/auth`; as demais rotas de conta mapeadas no gateway ainda dependem de implementação.

## Cadastro público — etapa 2

`POST /api/v1/auth/cadastro`, com `Content-Type: application/json`, recebe `nome`, `email`, `senha` e `perfil` (`comprador` ou `artesao`). O alias legado `papel` também é aceito. Se ambos forem enviados, devem ser iguais. Qualquer valor não público, conflito, tipo não textual, campo conhecido explicitamente nulo ou chave JSON duplicada resulta em 400; os valores de perfil são exatos, sem coerção ou normalização. Na ausência dos dois campos de perfil, o padrão de compatibilidade é `comprador`.

O servidor gera o UUID e normaliza nome/e-mail pelas regras existentes. A senha mantém a política de pelo menos 12 caracteres Unicode, UTF-8 válido, até 72 bytes e nenhum caractere de controle; não sofre trim. Tentativas de fornecer identificador, hash, privilégios ou indicador administrativo não alteram a conta criada. O papel também é validado pelo caso de uso, antes de acesso ao banco ou codificação.

Sucesso retorna **201** e o envelope abaixo, com dados normalizados:

```json
{
  "dados": {
    "id": "<uuid gerado pelo servidor>",
    "nome": "Pessoa Exemplo",
    "email": "pessoa@example.test",
    "papel": "comprador",
    "perfil": "comprador",
    "imagem": ""
  },
  "erro": null
}
```

`imagem` vazia permite a apresentação sem foto no frontend; não há criação de ateliê, slug, perfil ou foto nesta etapa. Campos de apresentação adicionais enviados pela UI, como território e técnica, não são persistidos pelo cadastro de contas. Esses dados pertencem à entrega de perfil do artesão (PI-171).

Erros preservam `{ "dados": null, "erro": { "codigo": "...", "mensagem": "..." } }`: **400 / REQUISICAO_INVALIDA** para entrada inválida, **409 / EMAIL_EM_USO** para duplicidade normalizada (inclusive concorrente) e **500 / CADASTRO_INDISPONIVEL** para falha interna sanitizada. Senhas, hashes e detalhes internos não são devolvidos.

O cadastro persiste somente hash bcrypt com salt; usa o índice único existente para impedir duas contas com o mesmo e-mail normalizado. Não foi necessária nova migration. A etapa 3 acrescenta o cookie JWT após a confirmação transacional da conta, preservando esse corpo de resposta. Se a assinatura falhar depois do commit, retorna 500 sem cookie; a conta permanece cadastrada e pode tentar login.

## Autenticação e sessão — etapa 3

- `POST /api/v1/auth/login` e alias temporário `/api/v1/auth/entrar`: recebem JSON com `email` e `senha`; retornam 200 com `{dados:usuarioPublico,erro:null}` e cookie `balaio_sessao`.
- `POST /api/v1/auth/cadastro`: retorna 201 e emite o mesmo cookie após persistir a conta.
- `POST /api/v1/auth/logout`: retorna 200 com `{dados:{encerrada:true},erro:null}` e expira o cookie, mesmo sem sessão ou com um token inválido/expirado.

Login usa e-mail normalizado, senha literal e bcrypt existentes; não aplica retroativamente a política de cadastro. UUID e papel vêm exclusivamente da conta persistida, independentemente de campos extras enviados pelo cliente. Conta ausente e senha incorreta retornam o mesmo **401 / CREDENCIAIS_INVALIDAS**; uma comparação bcrypt fictícia, com hash gerado no startup, reduz a diferença de processamento entre esses caminhos. Dados inválidos retornam **400 / REQUISICAO_INVALIDA** e falhas internas de autenticação retornam **500 / AUTENTICACAO_INDISPONIVEL**, sem expor causas ou credenciais. Erros mantêm `{dados:null,erro:{codigo,mensagem}}` e não emitem sessão.

O DTO continua limitado a ID, nome, e-mail, papel, perfil e imagem. Para administrador, `perfil` é omitido, pois o contrato desse campo só admite comprador/artesão; `papel` permanece `admin`. Os sucessos de cadastro/login/logout incluem `Cache-Control: no-store`. JWT, hash e senha não aparecem no corpo de resposta.

JWT é assinado com **RS256** usando Spring Security JOSE gerido pelo BOM do projeto. Contém somente `sub` (UUID), `papel`, `iss`, `aud`, `iat`, `exp` e `jti`. O emissor usa UTC, identificador único por emissão e validade fixa de **3600 segundos**, sem refresh token. `organizador` permanece reservado para PI-210: a base atual persiste comprador/artesão/admin e o cadastro público não concede privilégios adicionais. [Referência técnica do Spring Security](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

Cookie: `balaio_sessao`, `HttpOnly`, `SameSite=Lax`, `Path=/api/v1`, sem `Domain`, `Max-Age=3600` e `Secure` por padrão. Logout usa o mesmo escopo e `Max-Age=0`. **Logout não revoga imediatamente o JWT**: uma cópia continua criptograficamente válida até `exp`, salvo políticas adicionais documentadas dos consumidores. Não há tabela de sessões, refresh ou lista de revogação.

### Configuração obrigatória por ambiente

O serviço exige a configuração no startup e falha com mensagem sanitizada se ela estiver ausente/inválida:

- `JWT_PRIVATE_KEY_LOCATION`: caminho absoluto ou URI `file:` de arquivo PEM PKCS#8 RSA CRT, no mínimo 3072 bits. Não aceita `classpath:`, URL remota, chave pública no lugar da privada, arquivo vazio/inválido ou chave fraca. O conteúdo não deve ser colocado em uma variável de ambiente.
- `JWT_ISSUER`: emissor não vazio acordado com os consumidores.
- `JWT_AUDIENCE`: audiência não vazia acordada com os consumidores.
- `AUTH_ALLOWED_ORIGINS`: origens públicas exatas separadas por vírgula, com esquema/host/porta e sem caminho, query, fragmento, credenciais ou wildcard. Exemplo local: `http://localhost:4321`; `127.0.0.1` é uma origem diferente.
- `AUTH_COOKIE_SECURE`: padrão `true`. `false` só é aceito com **único perfil ativo `local`** e todas as origens permitidas usando HTTP. Mesmo nesse modo, uma requisição HTTPS direta emite `Secure`. `local` junto de outro perfil não habilita a exceção.

O profile deriva a pública correspondente e executa um self-check de assinatura/verificação antes de concluir a configuração. Não gera uma chave nova no startup de produção. Os testes geram material descartável em execução e não versionam chaves privadas.

Provisionar o material fora de todos os repositórios, com acesso restrito à identidade do processo e mount somente leitura. Em um terminal com OpenSSL, estes comandos exemplificam o provisionamento **manual**, usando caminhos externos escolhidos pelo operador:

```text
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out <arquivo-privado-externo.pem>
openssl pkey -in <arquivo-privado-externo.pem> -pubout -out <arquivo-publico-externo.pem>
```

Somente o profile recebe o arquivo privado. Gateway e serviços receberão apenas o arquivo **público** por configuração, usando os mesmos valores de issuer/audience na integração futura. Não distribuir a privada, imprimir seus componentes ou gerar arquivos dentro do Git. Os comandos são exemplos de procedimento, não foram executados para criar uma chave de ambiente nesta entrega. [OpenSSL genpkey](https://docs.openssl.org/3.5/man1/openssl-genpkey/), [OpenSSL pkey](https://docs.openssl.org/3.5/man1/openssl-pkey/).

O Compose atual precisa receber o mount e essas variáveis para iniciar o profile; esta etapa não altera `platform` ou gateway. Uma substituição de chave deve ser coordenada com os consumidores: não há JWKS/rotação dinâmica nesta entrega e tokens emitidos com a chave anterior podem deixar de ser aceitos antes dos 60 minutos.

### Origin e limites da integração

Cadastro, login/alias e logout verificam `Origin` antes dos efeitos. Quando presente, ele deve corresponder exatamente à allowlist; `null`, múltiplos valores e origem externa retornam **403 / SEM_PERMISSAO**. Se houver cookie `balaio_sessao`, a ausência de Origin também é rejeitada. Requisições sem cookie e sem Origin são permitidas para clientes de API; adicionar Bearer não dispensa a regra quando há cookie. Para repetir logout com cookie no Bruno, enviar Origin permitido ou remover o cookie do cliente.

Não há confiança em `Host`, `Forwarded` ou `X-Forwarded-*` para desligar Secure; `server.forward-headers-strategy=none`. HTTPS público deve usar `AUTH_COOKIE_SECURE=true`, inclusive quando o TLS terminar no proxy. O CORS do gateway e a propagação de credenciais são trabalho da etapa 4; esta etapa não adiciona CORS permissivo.

O cookie restrito a `/api/v1` não chega ao SSR de `/account` ou `/dashboard`; esse desenho continua registrado no acompanhamento. Sessões opacas da Fake API não reconhecem JWT real. Cookies legados com `Path=/` precisam de limpeza no corte de integração; o logout atual limpa o cookie contratado com `Path=/api/v1`.

### Consulta do usuário autenticado

`GET /api/v1/usuario` recebe exatamente um `Authorization: Bearer <JWT>` e revalida a assinatura **RS256** com a chave pública local derivada da privada configurada. O gateway encaminha nesse header o token validado do cookie; uma chamada direta ao profile também precisa de Bearer. Cookies ou headers de identidade, UUID e papel enviados pelo cliente não autenticam essa rota.

Os sete claims são obrigatórios: `sub` UUID canônico, `papel` conhecido no domínio atual, `iss` e `aud` correspondentes à configuração, `iat` e `exp` NumericDate inteiros e `jti` textual não vazio. A validade deve ser positiva e de até 3600 segundos; `iat` admite até 30 segundos no futuro, enquanto `exp` é estrito, inclusive no instante exato de expiração. Se presente, `nbf` também é respeitado sem tolerância. Tipos inválidos, campos JSON duplicados, algoritmos alternativos e headers `jwk`, `jku`, `x5u` ou `x5c` são rejeitados; nenhuma chave remota é consultada.

Após a validação, o serviço consulta `ContasPort.buscarPorId` e retorna **200** com o mesmo `{dados:usuarioPublico,erro:null}` de login, usando nome, e-mail e papel **atuais do PostgreSQL**, mesmo se o papel do JWT tiver mudado desde a emissão. `perfil` continua omitido para administrador; senha, hash, JWT e indicador de troca não são expostos. Token ausente/inválido ou conta inexistente produzem o mesmo **401 / NAO_AUTENTICADO**, com envelope sanitizado. Sucesso e 401 incluem `Cache-Control: no-store` e não emitem cookie.

Essa validação fica restrita à consulta de usuário. Regras de autorização dos demais recursos e cadeias globais de segurança dos serviços permanecem nas etapas próprias.

## Contas e primeiro administrador

O domínio de `account` não depende de Spring ou JPA. Contas têm um único papel (`comprador`, `artesao` ou `admin`), e-mail normalizado/único, hash bcrypt e `trocaSenhaObrigatoria`. O cadastro público aceita exclusivamente `comprador` ou `artesao`; `admin` e `organizador` são rejeitados. A instrução da etapa 2 substitui a regra anterior que sempre cadastrava comprador.

O bootstrap usa `ADMIN_EMAIL` e `ADMIN_SENHA_INICIAL` enquanto o marcador estiver pendente. Cria o administrador com troca obrigatória e confirma o controle na mesma transação, sob bloqueio no PostgreSQL. Depois da conclusão, reinícios não redefinem senha ou papel. Credenciais não são gravadas no código nem registradas em mensagens.

`spring-security-crypto` fornece o `PasswordEncoder` bcrypt de custo 12. `spring-security-oauth2-jose` fornece assinatura RS256; não foi adicionada cadeia global de autenticação HTTP. `spring-boot-testcontainers` e o módulo PostgreSQL existem somente em escopo de teste.

## Alteração da própria senha

`AlterarPropriaSenhaUseCase.alterar` recebe `IdentidadeAutenticada` separada do comando. O comando contém somente `senhaAtual` e `novaSenha`; não aceita UUID de alvo, papel ou indicador. A identidade é um contrato interno para a futura fronteira de autenticação validada. Não existe mecanismo provisório que confie em um UUID informado pelo cliente.

A aplicação confere a senha atual usando `PasswordEncoder.matches`, valida a nova com `PoliticaSenha` e rejeita reutilização. A política exige pelo menos 12 caracteres Unicode, UTF-8 válido, até 72 bytes e nenhum caractere de controle. As senhas não sofrem trim ou interpolação. O caso de uso não retorna senha, hash ou dados da conta; o comando oculta suas credenciais em `toString` e erros inesperados são sanitizados sem causa sensível.

`Conta.alterarSenha` exige um hash novo e produz uma conta com a obrigação desativada, preservando os demais dados. `ContasPort.atualizarSenha` faz uma atualização condicional: grava somente hash e `troca_senha_obrigatoria = false` se o hash anterior ainda corresponder ao lido. A transação confirma ambos ou nenhum. Uma segunda troca concorrente usando o mesmo estado recebe conflito; não há repetição automática com senha antiga. O cache JPA é sincronizado para permitir releitura correta na mesma transação.

Não há operação pública para desativar somente o indicador. O valor continua persistido na coluna já existente de V1; esta etapa não exige nova migration.

## Autorização administrativa interna

Os casos de uso administrativos devem abrir uma transação de escrita e chamar `AutorizarOperacaoAdministrativaUseCase.exigirPermissao` antes de qualquer efeito, dentro dessa mesma transação. O guard exige transação existente (`MANDATORY`), consulta o estado atual da conta com `SELECT ... FOR UPDATE` e exige papel `admin` e `trocaSenhaObrigatoria = false`. A trava é mantida até o fim da ação protegida.

Uma identidade ausente, conta indisponível, papel inadequado ou troca pendente bloqueia a operação. Claims futuros de papel/indicador não substituirão a consulta ao estado persistido. A troca da própria senha não depende desse guard, portanto permanece permitida enquanto a obrigação estiver pendente. A alteração administrativa de papéis descrita abaixo já utiliza essa autorização; nenhum endpoint é exposto nesta etapa.

## Alteração administrativa de papéis e último administrador

`AlterarPapelAdministrativoUseCase.alterar` recebe a identidade confiável do solicitante separada de `AlterarPapelComando`, que descreve apenas a conta alvo e seu novo papel. O UUID do alvo e o papel solicitado não representam autorização. A futura autenticação JWT deverá produzir a identidade a partir do token validado, sem copiá-la do corpo ou de um identificador livre informado pelo cliente.

A operação permite atribuir `comprador`, `artesao` ou `admin`, sempre consultando no banco o papel e a pendência de senha atuais do solicitante. Uma solicitação sem mudança de papel também exige autorização. O cadastro público permanece restrito a comprador/artesão e não utiliza esta entrada administrativa.

O caso de uso abre uma transação independente `REQUIRES_NEW`, com isolamento `READ_COMMITTED`. A ordem é: bloquear o singleton `controle_administracao`; autorizar e bloquear o solicitante; bloquear a conta alvo; contar administradores; aplicar a regra de domínio; atualizar somente o papel. A comparação com o papel anterior também protege a escrita contra estado incompatível, sem sobrescrever nome, e-mail, hash ou indicador. O cache JPA é sincronizado após a atualização.

`Conta.alterarPapel` lança `UltimoAdministradorException` se um administrador perder esse papel quando só existe um. A mesma regra se aplica à própria conta. A exceção é de negócio, sem dependência HTTP, e poderá ser traduzida para 409. Com dois administradores, uma despromoção pode confirmar e o restante passa a ficar protegido.

A trava do singleton permanece até commit ou rollback. Assim, a segunda operação concorrente conta os administradores depois da conclusão da primeira. `REQUIRES_NEW` evita herdar um snapshot antigo de uma transação externa `REPEATABLE_READ`; a contagem precisa refletir o estado confirmado após a espera. Esta é uma fronteira transacional independente: não a invoque de uma transação externa que já detenha o singleton ou as mesmas contas, para evitar bloqueio contra a própria transação suspensa.

O modelo atual não possui indicador de ativação nem operações de remoção/desativação. Portanto, contas existentes com papel `admin` compõem a contagem de administradores ativos nesta etapa. Pendência de troca restringe ações administrativas, mas não equivale a desativação. Não foram criadas operações de ciclo de vida; quando existirem, deverão reutilizar a trava e a verificação do último administrador antes de remover sua condição ativa. Curadoria de selo pertence à PI-177 e não integra esta entrega.

## Testes e escopo

Execute `.\mvnw.cmd -B -ntp clean verify` com JDK 25 e Docker ativo. A compilação limpa também remove classes e migrations antigas de `target/` depois de desfazer alterações. Os testes usam PostgreSQL 18 descartável via Testcontainers, sem usar o banco do projeto nem ler credenciais locais. Confira `java -version` e `JAVA_HOME` no terminal usado pelo Maven; a versão declarada no POM não seleciona automaticamente um JDK instalado.

Os testes de cadastro incluem comprador/artesão, ambos os aliases de perfil, payloads manipulados, dados inválidos, JSON bruto duplicado, ausência de credenciais/cookie nas respostas, duplicidade e corrida simultânea de e-mail normalizado. A corrida força os dois SELECTs a concluírem antes dos inserts, exercitando a restrição única real do PostgreSQL.

Os testes de `GET /api/v1/usuario` fazem chamadas HTTP reais com PostgreSQL descartável, verificam os dados e papéis atualizados após a emissão do token, ausência de dados sensíveis, conta removida, headers forjados e cookies sem Bearer. A validação criptográfica exercita assinatura incorreta, algoritmos alternativos, claims ausentes ou com tipos inválidos, JSON duplicado, chaves remotas, issuer/audience, validade máxima, tolerância de `iat` e expiração estrita com relógio controlado.

Os testes cobrem bootstrap com indicador pendente, senha atual correta/incorreta, política e não reutilização, hash novo e senha anterior inválida, remoção da obrigação somente no sucesso, rollback após escrita real, isolamento da própria conta, conflitos entre duas transações concorrentes e guard administrativo antes/depois da troca. Também verificam releitura na mesma transação e ausência de segredos nas representações e erros.

Para papéis, os testes verificam autorização pelo estado atual do banco, bloqueio de solicitantes comuns/pendentes, promoção válida, preservação dos demais dados, impossibilidade de autodespromover o último administrador, proteção do remanescente, rollback e duas despromoções concorrentes sob bloqueio PostgreSQL real. Também exercitam a entrada a partir de snapshots externos `REPEATABLE_READ` e tentativas de usar o identificador do alvo como autorização.

O cadastro da etapa 2 inclui adaptador HTTP, aplicação e persistência de contas. A etapa 3 acrescenta login, JWT, cookie e logout, restritos ao profile. Gateway e demais microsserviços não foram alterados. Bootstrap, troca de senha e administração continuam internos, sem novos endpoints administrativos.
