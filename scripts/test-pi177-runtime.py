"""Integração real: Profile, Catalog, Gateway, Frontend e dois PostgreSQL isolados.

Uso: python scripts/test-pi177-runtime.py target/pi177-profile.jar [--browser]
Pré-requisitos: imagens locais balaio/{catalog-service,api-gateway,frontend}:local.
Cria recursos com nomes aleatórios e remove somente esses recursos ao terminar.
"""
import json
import os
import pathlib
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid


def docker(*args):
    result = subprocess.run(["docker", *args], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("Operacao Docker de teste falhou: " + args[0])
    return result.stdout.strip()


def wait_url(url):
    deadline = time.monotonic() + 120
    while True:
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            pass
        if time.monotonic() > deadline:
            raise RuntimeError("Servico de teste nao iniciou: " + url)
        time.sleep(1)


def wait_container(name, port):
    deadline = time.monotonic() + 120
    while subprocess.run(["docker", "exec", name, "wget", "-qO-", f"http://127.0.0.1:{port}/actuator/health"], capture_output=True).returncode:
        if time.monotonic() > deadline:
            raise RuntimeError("Servico de teste nao iniciou: " + name)
        time.sleep(1)


def main():
    jar = pathlib.Path(sys.argv[1]).resolve()
    if not jar.is_file():
        raise RuntimeError("Execute o build do Profile antes do teste.")
    prefix = "pi177-" + uuid.uuid4().hex[:10]
    containers = []
    checks = 0
    profile_db, catalog_db, profile, catalog, gateway, frontend = [prefix + "-" + x for x in
        ("profile-db", "catalog-db", "profile", "catalog", "gateway", "frontend")]
    password = "Pi177!" + secrets.token_hex(12)
    admin_password = "Admin!" + secrets.token_hex(12)
    admin_initial = "Inicial!" + secrets.token_hex(12)
    admin_email = "admin@pi177.test"
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        frontend_port = sock.getsockname()[1]
    frontend_url = f"http://127.0.0.1:{frontend_port}"
    with tempfile.TemporaryDirectory(prefix="balaio-pi177-") as temporary:
        directory = pathlib.Path(temporary)
        generator = directory / "keys.ps1"
        generator.write_text("""param([string] $Directory)
$rsa = [Security.Cryptography.RSA]::Create(3072)
[IO.File]::WriteAllText((Join-Path $Directory 'private.pem'), $rsa.ExportPkcs8PrivateKeyPem())
[IO.File]::WriteAllText((Join-Path $Directory 'public.pem'), $rsa.ExportSubjectPublicKeyInfoPem())
$rsa.Dispose()
""", encoding="utf-8")
        subprocess.run(["pwsh", "-NoProfile", "-File", str(generator), "-Directory", temporary], check=True, capture_output=True)

        def run(name, *args):
            docker("run", "-d", "--name", name, "--network", prefix, *args)
            containers.append(name)

        def sql(db, statement):
            return docker("exec", db, "psql", "-U", "pi177", "-d", "pi177", "-Atc", statement)

        try:
            docker("network", "create", prefix)
            for db in (profile_db, catalog_db):
                run(db, "-v", "/var/lib/postgresql", "-e", "POSTGRES_DB=pi177", "-e", "POSTGRES_USER=pi177",
                    "-e", "POSTGRES_PASSWORD=" + password, "postgres:18-alpine")
                deadline = time.monotonic() + 60
                while subprocess.run(["docker", "exec", db, "pg_isready", "-U", "pi177"], capture_output=True).returncode:
                    if time.monotonic() > deadline:
                        raise RuntimeError("Banco de teste nao iniciou.")
                    time.sleep(1)
            run(profile, "--mount", f"type=bind,source={jar},target=/app.jar,readonly",
                "--mount", f"type=bind,source={directory / 'private.pem'},target=/private.pem,readonly",
                "-p", "127.0.0.1::8082", "-e", "SPRING_PROFILES_ACTIVE=local", "-e", "AUTH_COOKIE_SECURE=false",
                "-e", f"SPRING_DATASOURCE_URL=jdbc:postgresql://{profile_db}:5432/pi177",
                "-e", "SPRING_DATASOURCE_USERNAME=pi177", "-e", "SPRING_DATASOURCE_PASSWORD=" + password,
                "-e", "JWT_PRIVATE_KEY_LOCATION=file:/private.pem", "-e", "JWT_ISSUER=pi177-test",
                "-e", "JWT_AUDIENCE=pi177-api", "-e", "AUTH_ALLOWED_ORIGINS=" + frontend_url,
                "-e", f"CATALOG_SERVICE_URL=http://{catalog}:8081", "-e", "ADMIN_EMAIL=" + admin_email,
                "-e", "ADMIN_SENHA_INICIAL=" + admin_initial,
                "eclipse-temurin:25-jre-alpine", "java", "-jar", "/app.jar")
            profile_port = docker("port", profile, "8082/tcp").rsplit(":", 1)[1]
            wait_url("http://127.0.0.1:" + profile_port + "/actuator/health")
            run(catalog, "--mount", f"type=bind,source={directory / 'public.pem'},target=/public.pem,readonly",
                "-e", f"CATALOG_DB_URL=jdbc:postgresql://{catalog_db}:5432/pi177", "-e", "CATALOG_DB_USERNAME=pi177",
                "-e", "CATALOG_DB_PASSWORD=" + password, "-e", "JWT_PUBLIC_KEY_LOCATION=file:/public.pem",
                "-e", "JWT_ISSUER=pi177-test", "-e", "JWT_AUDIENCE=pi177-api",
                "-e", f"PROFILE_SERVICE_URL=http://{profile}:8082", "balaio/catalog-service:local")
            wait_container(catalog, 8081)
            run(gateway, "-p", "127.0.0.1::8080", "--mount", f"type=bind,source={directory / 'public.pem'},target=/public.pem,readonly",
                "-e", f"PROFILE_SERVICE_URL=http://{profile}:8082", "-e", f"CATALOG_SERVICE_URL=http://{catalog}:8081",
                "-e", "JWT_PUBLIC_KEY_LOCATION=file:/public.pem", "-e", "JWT_ISSUER=pi177-test",
                "-e", "JWT_AUDIENCE=pi177-api", "-e", "AUTH_ALLOWED_ORIGINS=" + frontend_url,
                "balaio/api-gateway:local")
            port = docker("port", gateway, "8080/tcp").rsplit(":", 1)[1]
            base = "http://127.0.0.1:" + port
            wait_url(base + "/actuator/health")

            def api(method, path, body=None, token=None, expected=200):
                nonlocal checks
                headers = {"Content-Type": "application/json"}
                if token:
                    headers["Authorization"] = "Bearer " + token
                request = urllib.request.Request(base + "/api/v1" + path,
                    data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
                try:
                    response = urllib.request.urlopen(request, timeout=20)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    payload = json.loads(response.read())
                    if response.status != expected:
                        raise AssertionError(f"{method} {path}: esperado {expected}, recebido {response.status}; {payload.get('erro')}")
                    checks += 1
                    cookie = response.headers.get("Set-Cookie", "")
                    token_out = cookie.split(";", 1)[0].partition("=")[2] if cookie else None
                    return payload.get("dados"), token_out

            _, admin_token = api("POST", "/auth/login", {"email": admin_email, "senha": admin_initial})
            api("GET", "/admin/curadoria", token=admin_token, expected=403)
            api("POST", "/auth/alterar-senha", {"senhaAtual": admin_initial, "novaSenha": admin_password}, admin_token)
            _, admin_token = api("POST", "/auth/login", {"email": admin_email, "senha": admin_password})
            accounts = []
            for role in ("artesao", "artesao", "comprador", "artesao"):
                email = uuid.uuid4().hex + "@pi177.test"
                account, token = api("POST", "/auth/cadastro", {"nome": "Conta PI177", "email": email, "senha": password, "perfil": role}, expected=201)
                accounts.append((account, token, email))
            owner, token, _ = accounts[0]
            slug = "artesao-" + owner["id"]
            cultural = {"nome": "Artista PI177", "atelie": "Ateliê PI177", "historia": "História preservada", "territorio": "Pajeú", "tecnica": "Xilogravura"}
            api("PATCH", "/artesao/me", cultural, token)
            profile_before, _ = api("GET", "/artesaos/" + slug)
            api("GET", "/artesao/selo", expected=401)
            api("GET", "/admin/curadoria", token=token, expected=403)
            api("POST", "/artesao/selo", {"mensagem": "Teste"}, accounts[2][1], 403)
            state, _ = api("POST", "/artesao/selo", {"mensagem": "Confirmar autoria"}, token)
            first_id = state["solicitacao"]["id"]
            api("POST", "/artesao/selo", {"mensagem": "Duplicada"}, token, 409)
            other_state, _ = api("GET", "/artesao/selo", token=accounts[1][1])
            assert other_state["solicitacao"] is None
            api("POST", f"/admin/curadoria/{first_id}/decisao", {"decisao": "aprovada", "motivo": ""}, admin_token, 409)
            api("POST", f"/admin/curadoria/{first_id}/decisao", {"decisao": "ajuste", "motivo": "Publicar uma peça"}, admin_token)
            state, _ = api("GET", "/artesao/selo", token=token)
            assert state["solicitacao"]["motivo"] == "Publicar uma peça"
            state, _ = api("POST", "/artesao/selo", {"mensagem": "Peça preparada"}, token)
            request_id = state["solicitacao"]["id"]
            assert request_id != first_id
            piece_body = {"nome": "Peça real PI177", "tecnica": "Cerâmica & Barro", "territorio": "Caruaru",
                "categoria": "Casa & Mesa", "tipo": "Jarras & Vasos", "historia": ["História da peça"], "preco": 75,
                "disponibilidade": "disponivel", "situacao": "publicada", "fotos": []}
            piece, _ = api("POST", "/pecas", piece_body, token, 201)
            piece_before, _ = api("GET", "/pecas/" + piece["slug"])
            queue, _ = api("GET", "/admin/curadoria", token=admin_token)
            assert next(item for item in queue if item["id"] == request_id)["pecasPublicadas"] == 1
            decision = {"decisao": "aprovada", "motivo": ""}
            first_result, _ = api("POST", f"/admin/curadoria/{request_id}/decisao", decision, admin_token)
            repeat, _ = api("POST", f"/admin/curadoria/{request_id}/decisao", decision, admin_token)
            assert first_result == repeat
            api("POST", f"/admin/curadoria/{request_id}/decisao", {"decisao": "ajuste", "motivo": "Conflitante"}, admin_token, 409)
            public_profile, _ = api("GET", "/artesaos/" + slug)
            assert public_profile["selo"] is True
            for field in cultural:
                assert public_profile[field] == profile_before[field]
            assert public_profile["imagem"] == profile_before["imagem"]
            queue, _ = api("GET", "/admin/curadoria", token=admin_token)
            assert all(item["id"] != request_id for item in queue)
            assert sql(profile_db, f"SELECT count(*) FROM solicitacoes_selo WHERE perfil_id=(SELECT id FROM perfis_artesao WHERE conta_id='{owner['id']}')") == "2"
            # Reiniciar também o Postgres confirma que os dados não dependem de memória do processo.
            docker("restart", profile_db, catalog_db)
            docker("restart", profile, catalog)
            # Docker pode realocar uma porta publicada automaticamente ao reiniciar.
            profile_port = docker("port", profile, "8082/tcp").rsplit(":", 1)[1]
            wait_url("http://127.0.0.1:" + profile_port + "/actuator/health")
            wait_container(catalog, 8081)
            state, _ = api("GET", "/artesao/selo", token=token)
            assert state["selo"] is True and state["solicitacao"]["id"] == request_id
            public_profile, _ = api("GET", "/artesaos/" + slug)
            assert public_profile["selo"] is True
            piece_after, _ = api("GET", "/pecas/" + piece["slug"])
            assert piece_before == piece_after, "Curadoria alterou peça, autenticidade ou publicação"
            print(f"PI-177 HTTP real: {checks} verificacoes aprovadas; persistencia apos reinicio e Catalog inalterado.", flush=True)

            if "--browser" in sys.argv:
                browser_owner, browser_token, browser_email = accounts[3]
                browser_slug = "artesao-" + browser_owner["id"]
                api("PATCH", "/artesao/me", {**cultural, "atelie": "Ateliê Navegador PI177"}, browser_token)
                api("POST", "/pecas", {**piece_body, "nome": "Peça do navegador PI177"}, browser_token, 201)
                run(frontend, "-p", f"127.0.0.1:{frontend_port}:7879", "-e", f"API_GATEWAY_URL=http://{gateway}:8080/api/v1",
                    "-e", "AUTH_ALLOWED_ORIGINS=" + frontend_url, "balaio/frontend:local")
                wait_url(frontend_url + "/healthz")
                front_root = pathlib.Path(__file__).resolve().parents[2] / "Balaio-Front" / "Frontend"
                environment = {**os.environ, "PI177_BASE_URL": frontend_url, "PI177_ARTESAO_EMAIL": browser_email,
                    "PI177_ARTESAO_SENHA": password, "PI177_ADMIN_EMAIL": admin_email, "PI177_ADMIN_SENHA": admin_password,
                    "PI177_SLUG": browser_slug}
                result = subprocess.run(["node", "tests/pi177-real-browser.cjs"], cwd=front_root, env=environment,
                    text=True, capture_output=True, timeout=180)
                print(result.stdout.strip(), flush=True)
                if result.returncode:
                    raise RuntimeError("Falha no navegador: " + result.stderr[-1800:])
                browser_state, _ = api("GET", "/artesao/selo", token=browser_token)
                assert browser_state["selo"] is True
                # Falha controlada SOMENTE no banco descartável: consultas de selo falham,
                # mas autenticação e a projeção pública continuam disponíveis.
                sql(profile_db, "ALTER TABLE solicitacoes_selo RENAME COLUMN mensagem TO mensagem_fault")
                try:
                    result = subprocess.run(["node", "tests/pi177-real-browser.cjs"], cwd=front_root,
                        env={**environment, "PI177_EXPECT_SELO_FAILURE": "1"}, text=True, capture_output=True, timeout=90)
                    print(result.stdout.strip(), flush=True)
                    if result.returncode:
                        raise RuntimeError("Falha no teste de erro SSR: " + result.stderr[-1800:])
                finally:
                    sql(profile_db, "ALTER TABLE solicitacoes_selo RENAME COLUMN mensagem_fault TO mensagem")
        finally:
            for name in reversed(containers):
                subprocess.run(["docker", "rm", "-f", "-v", name], capture_output=True)
            subprocess.run(["docker", "network", "rm", prefix], capture_output=True)


if __name__ == "__main__":
    main()
