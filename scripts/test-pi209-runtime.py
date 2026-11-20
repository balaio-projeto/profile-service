"""HTTP real via Gateway, PostgreSQL descartavel e reinicio do Profile.

Executar apos mvn verify: python scripts/test-pi209-runtime.py target/pi209-profile.jar
Nao usa Compose, credenciais ou volumes da instalacao existente.
"""
import json
import pathlib
import secrets
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


def main():
    jar = pathlib.Path(sys.argv[1]).resolve()
    if not jar.is_file():
        raise RuntimeError("Execute o build antes do teste de runtime.")
    suffix = uuid.uuid4().hex[:10]
    network = "pi209-" + suffix
    db, profile, gateway = (network + name for name in ("-db", "-profile", "-gateway"))
    containers = []
    checks = 0
    with tempfile.TemporaryDirectory(prefix="balaio-pi209-") as temporary:
        directory = pathlib.Path(temporary)
        generator = directory / "keys.ps1"
        generator.write_text("""param([string] $Directory)
$rsa = [Security.Cryptography.RSA]::Create(3072)
[IO.File]::WriteAllText((Join-Path $Directory 'private.pem'), $rsa.ExportPkcs8PrivateKeyPem())
[IO.File]::WriteAllText((Join-Path $Directory 'public.pem'), $rsa.ExportSubjectPublicKeyInfoPem())
$rsa.Dispose()
""", encoding="utf-8")
        subprocess.run(["pwsh", "-NoProfile", "-File", str(generator), "-Directory", temporary], check=True, capture_output=True)
        try:
            docker("network", "create", network)
            containers.append(db)
            docker("run", "-d", "--name", db, "--network", network, "--tmpfs", "/var/lib/postgresql",
                   "-e", "POSTGRES_DB=pi209", "-e", "POSTGRES_USER=pi209", "-e", "POSTGRES_PASSWORD=pi209-test-only",
                   "postgres:18-alpine")
            deadline = time.monotonic() + 60
            while subprocess.run(["docker", "exec", db, "pg_isready", "-U", "pi209"], capture_output=True).returncode:
                if time.monotonic() > deadline:
                    raise RuntimeError("PostgreSQL de teste nao iniciou.")
                time.sleep(1)
            containers.append(profile)
            docker("run", "-d", "--name", profile, "--network", network,
                   "--mount", f"type=bind,source={jar},target=/app.jar,readonly",
                   "--mount", f"type=bind,source={directory / 'private.pem'},target=/private.pem,readonly",
                   "-e", "SPRING_PROFILES_ACTIVE=local", "-e", "AUTH_COOKIE_SECURE=false",
                   "-e", f"SPRING_DATASOURCE_URL=jdbc:postgresql://{db}:5432/pi209",
                   "-e", "SPRING_DATASOURCE_USERNAME=pi209", "-e", "SPRING_DATASOURCE_PASSWORD=pi209-test-only",
                   "-e", "JWT_PRIVATE_KEY_LOCATION=file:/private.pem", "-e", "JWT_ISSUER=pi209-test",
                   "-e", "JWT_AUDIENCE=pi209-api", "-e", "AUTH_ALLOWED_ORIGINS=http://localhost:4321",
                   "eclipse-temurin:25-jre-alpine", "java", "-jar", "/app.jar")

            def aguardar_profile():
                deadline = time.monotonic() + 90
                while subprocess.run(["docker", "exec", profile, "wget", "-qO-", "http://127.0.0.1:8082/actuator/health"], capture_output=True).returncode:
                    if time.monotonic() > deadline:
                        raise RuntimeError("Profile de teste nao iniciou.")
                    time.sleep(1)

            aguardar_profile()
            containers.append(gateway)
            docker("run", "-d", "--name", gateway, "--network", network, "-p", "127.0.0.1::8080",
                   "--mount", f"type=bind,source={directory / 'public.pem'},target=/public.pem,readonly",
                   "-e", f"PROFILE_SERVICE_URL=http://{profile}:8082", "-e", "JWT_PUBLIC_KEY_LOCATION=file:/public.pem",
                   "-e", "JWT_ISSUER=pi209-test", "-e", "JWT_AUDIENCE=pi209-api",
                   "-e", "AUTH_ALLOWED_ORIGINS=http://localhost:4321", "balaio/api-gateway:local")
            port = docker("port", gateway, "8080/tcp").rsplit(":", 1)[1]
            base = "http://127.0.0.1:" + port

            def api(method, path, body=None, token=None, expected=200):
                nonlocal checks
                headers = {"Content-Type": "application/json"}
                if token:
                    headers["Authorization"] = "Bearer " + token
                request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
                try:
                    response = urllib.request.urlopen(request, timeout=15)
                except urllib.error.HTTPError as error:
                    response = error
                payload = json.loads(response.read())
                if response.status != expected:
                    raise AssertionError(f"{method} {path}: esperado {expected}, recebido {response.status}")
                checks += 1
                cookie = response.headers.get("Set-Cookie", "")
                jwt = cookie.split(";", 1)[0].partition("=")[2] if cookie else None
                return payload.get("dados"), jwt

            deadline = time.monotonic() + 90
            while True:
                try:
                    urllib.request.urlopen(base + "/actuator/health", timeout=2).close()
                    break
                except (OSError, urllib.error.URLError):
                    if time.monotonic() > deadline:
                        raise RuntimeError("Gateway de teste nao iniciou.")
                    time.sleep(1)
            password = "Pi209-" + secrets.token_hex(12)
            accounts = []
            for role in ("artesao", "artesao", "comprador"):
                email = uuid.uuid4().hex + "@example.test"
                account, token = api("POST", "/api/v1/auth/cadastro", {"nome": "Conta PI209", "email": email, "senha": password, "perfil": role}, expected=201)
                accounts.append((account, token))
            owner, token = accounts[0]
            other, other_token = accounts[1]
            slug = "artesao-" + owner["id"]
            path = "/api/v1/artesaos/" + slug + "/configuracoes"
            cultural, _ = api("GET", "/api/v1/artesaos/" + slug)
            email = uuid.uuid4().hex + "@example.test"
            api("PATCH", "/api/v1/conta/" + owner["id"], {"nome": "Conta atualizada", "email": email}, token)
            api("PATCH", "/api/v1/conta/" + other["id"], {"nome": "Proibido"}, token, 403)
            api("PATCH", "/api/v1/usuario", {"email": "invalido"}, token, 400)
            api("PATCH", "/api/v1/usuario", {"email": other["email"]}, token, 409)
            api("GET", path, token=token)
            pix = "privada-" + suffix + "@example.test"
            config = {"cepOrigem": "55880-000", "prazoPadraoDias": 17, "aceitaEncomendas": True, "encomendasPausadas": False, "chavePix": pix}
            api("PATCH", path, config, token)
            api("PATCH", path, {"encomendasPausadas": True}, token)
            config["encomendasPausadas"] = True
            api("GET", path, token=other_token, expected=403)
            api("PATCH", path, {"chavePix": "invasor"}, other_token, 403)
            api("GET", path, expected=401)
            api("POST", "/api/v1/auth/alterar-senha", {"senhaAtual": "incorreta", "novaSenha": password + "!"}, token, 400)
            api("POST", "/api/v1/auth/alterar-senha", {"senhaAtual": password, "novaSenha": password + "!"}, token)
            api("POST", "/api/v1/auth/login", {"email": email, "senha": password}, expected=401)
            api("POST", "/api/v1/auth/login", {"email": email, "senha": password + "!"})
            docker("restart", profile)
            aguardar_profile()
            persisted, _ = api("GET", path, token=token)
            assert persisted == config, "Configuracoes perdidas apos reinicio."
            account, _ = api("GET", "/api/v1/usuario", token=token)
            assert account["nome"] == "Conta atualizada" and account["email"] == email
            after, _ = api("GET", "/api/v1/artesaos/" + slug)
            assert after == cultural, "O perfil cultural foi alterado pela edicao da conta."
            assert pix not in json.dumps(after)
            assert pix not in docker("logs", profile)
            print(f"PASS: {checks} verificacoes HTTP reais; persistencia apos reinicio; perfil cultural preservado; Pix privado.")
        finally:
            for name in reversed(containers):
                subprocess.run(["docker", "rm", "-f", "-v", name], capture_output=True)
            subprocess.run(["docker", "network", "rm", network], capture_output=True)


if __name__ == "__main__":
    main()
