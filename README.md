# profile-service

Contas, perfis de compradores e artesãos, selo e curadoria. O serviço é independente, usa Java 25 e Spring Boot 4.1.1 e recebe requisições externas por meio do api-gateway.

## Stack inicial

- Spring MVC, Spring Data JPA e PostgreSQL
- Flyway para migrações do banco próprio
- Bean Validation
- Spring Boot Actuator

## Execução

Porta HTTP padrão: 8082. Para executar pela IDE, defina `PROFILE_SERVICE_PORT` (opcional), `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD`. O banco e suas credenciais são configurados no repositório `platform`.

No Windows: `.\mvnw.cmd spring-boot:run`. Testes: `.\mvnw.cmd test`.

O `Dockerfile` constrói uma imagem própria. Em `platform`, use `docker compose up --build -d profile-service` para iniciar este serviço e seu banco ou `docker compose --profile extended up --build -d` para iniciar todos. Health: `http://localhost:8082/actuator/health`.

A migração inicial está em `src/main/resources/db/migration`. O gateway já encaminha `/api/v1/auth/**` para este serviço.

## Recuperação de senha

`POST /api/v1/auth/recuperacao` recebe `{ "email": "ana@exemplo.com" }` e retorna `201` com `{ "dados": { "recebida": true }, "erro": null }` para qualquer endereço válido, cadastrado ou não. A resposta não confirma a existência da conta. Para conta cadastrada, um segredo aleatório de 256 bits é enviado por e-mail; somente seu SHA-256 é salvo. Há um intervalo mínimo de um minuto entre pedidos para a mesma conta. Uma nova solicitação aceita invalida os links anteriores. O link dura 1 hora por padrão.

O e-mail aponta para `${PROFILE_FRONTEND_BASE_URL}/login/reset?token=...`. A página envia `POST /api/v1/auth/redefinicao` com `{ "token": "...", "novaSenha": "..." }`. Sucesso: `200` e `{ "dados": { "redefinida": true }, "erro": null }`. Token ausente, vencido ou usado: `400 TOKEN_INVALIDO`. A senha deve ter pelo menos 8 caracteres; erros de validação retornam `400 REQUISICAO_INVALIDA`. O consumo do token e a atualização da senha ocorrem na mesma transação; sessões anteriores são revogadas. Cadastro/login em `/api/v1/auth/cadastro` e `/api/v1/auth/login` usam BCrypt e emitem cookie `balaio_sessao` HttpOnly válido por sete dias. `/api/v1/usuario` lê a sessão e `/api/v1/auth/logout` a encerra.

Configure `PROFILE_FRONTEND_BASE_URL`, `PROFILE_RECOVERY_TOKEN_TTL` (duração ISO-8601, padrão `PT1H`), `PROFILE_MAIL_FROM`, `PROFILE_SMTP_HOST`, `PROFILE_SMTP_PORT`, `PROFILE_SMTP_USERNAME`, `PROFILE_SMTP_PASSWORD`, `PROFILE_SMTP_AUTH` e `PROFILE_SMTP_STARTTLS`. Em `platform`, copie `.env.example` para `.env` e preencha os valores do ambiente; `.env` é ignorado pelo Git. Use a URL pública real do frontend e um servidor SMTP de validação. Falhas de envio são registradas sem endereço, link, token ou senha; a resposta pública permanece uniforme. O envio ocorre após a transação, em tarefa assíncrona.

Para testar localmente com SMTP de captura, aponte `PROFILE_SMTP_HOST` e `PROFILE_SMTP_PORT` para esse servidor, crie uma conta por `/cadastro`, solicite a recuperação, abra o link recebido e autentique por `/login` com a senha nova. Sem servidor SMTP, os testes automatizados usam um emissor simulado. Execute `cmd /c ".\mvnw.cmd -Djava.version=21 test"` com JDK 21 disponível; o projeto também compila com o Java 25 declarado no Maven.
