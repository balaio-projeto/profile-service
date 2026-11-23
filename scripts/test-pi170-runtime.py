"""Recuperação real por SMTP/Mailpit, Gateway, Frontend e Postgres descartáveis.

Uso: python scripts/test-pi170-runtime.py
Pré-requisitos: imagens locais balaio/{profile-service,api-gateway,frontend}:local,
Docker, PowerShell 7 e Chrome/Playwright do frontend. Nunca lê o .env real.
"""
import json
import os
import pathlib
import secrets
import socket
import subprocess
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
            raise RuntimeError("Servico isolado nao iniciou.")
        time.sleep(1)


def main():
    prefix = "pi170-" + uuid.uuid4().hex[:10]
    db, mail, profile, gateway, frontend = [prefix + "-" + value for value in ("db", "mail", "profile", "gateway", "frontend")]
    owned = []
    password = "Fixture!" + secrets.token_hex(12)
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        frontend_port = sock.getsockname()[1]
    base = f"http://127.0.0.1:{frontend_port}"
    with tempfile.TemporaryDirectory(prefix="balaio-pi170-") as temporary:
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
            owned.append(name)

        try:
            docker("network", "create", prefix)
            run(db, "--tmpfs", "/var/lib/postgresql", "-e", "POSTGRES_DB=pi170", "-e", "POSTGRES_USER=pi170",
                "-e", "POSTGRES_PASSWORD=" + password, "postgres:18-alpine")
            deadline = time.monotonic() + 60
            while subprocess.run(["docker", "exec", db, "pg_isready", "-U", "pi170"], capture_output=True).returncode:
                if time.monotonic() > deadline:
                    raise RuntimeError("Postgres descartavel nao iniciou.")
                time.sleep(1)
            run(mail, "-p", "127.0.0.1::8025", "axllent/mailpit:v1.34.0")
            mail_port = docker("port", mail, "8025/tcp").rsplit(":", 1)[1]
            mail_url = "http://127.0.0.1:" + mail_port
            wait_url(mail_url + "/api/v1/messages")
            run(profile, "--mount", f"type=bind,source={directory / 'private.pem'},target=/private.pem,readonly",
                "-e", "SPRING_PROFILES_ACTIVE=local", "-e", "AUTH_COOKIE_SECURE=false",
                "-e", f"SPRING_DATASOURCE_URL=jdbc:postgresql://{db}:5432/pi170", "-e", "SPRING_DATASOURCE_USERNAME=pi170",
                "-e", "SPRING_DATASOURCE_PASSWORD=" + password, "-e", "JWT_PRIVATE_KEY_LOCATION=file:/private.pem",
                "-e", "JWT_ISSUER=pi170-test", "-e", "JWT_AUDIENCE=pi170-api", "-e", "AUTH_ALLOWED_ORIGINS=" + base,
                "-e", "PROFILE_SMTP_HOST=" + mail, "-e", "PROFILE_SMTP_PORT=1025",
                "-e", "PROFILE_SMTP_AUTH=false", "-e", "PROFILE_SMTP_STARTTLS=false",
                "-e", "PROFILE_MAIL_FROM=nao-responda@balaio.test", "-e", "PROFILE_FRONTEND_BASE_URL=" + base,
                "balaio/profile-service:local")
            run(gateway, "-p", "127.0.0.1::8080", "--mount", f"type=bind,source={directory / 'public.pem'},target=/public.pem,readonly",
                "-e", f"PROFILE_SERVICE_URL=http://{profile}:8082", "-e", "JWT_PUBLIC_KEY_LOCATION=file:/public.pem",
                "-e", "JWT_ISSUER=pi170-test", "-e", "JWT_AUDIENCE=pi170-api", "-e", "AUTH_ALLOWED_ORIGINS=" + base,
                "balaio/api-gateway:local")
            gateway_port = docker("port", gateway, "8080/tcp").rsplit(":", 1)[1]
            gateway_url = "http://127.0.0.1:" + gateway_port
            wait_url(gateway_url + "/actuator/health")
            deadline = time.monotonic() + 120
            while subprocess.run(["docker", "exec", profile, "wget", "-qO-", "http://127.0.0.1:8082/actuator/health"], capture_output=True).returncode:
                if time.monotonic() > deadline:
                    raise RuntimeError("Profile descartavel nao iniciou.")
                time.sleep(1)

            def api(path, body, expected):
                request = urllib.request.Request(gateway_url + "/api/v1" + path, data=json.dumps(body).encode(),
                    headers={"Content-Type": "application/json"}, method="POST")
                try:
                    response = urllib.request.urlopen(request, timeout=20)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    if response.status != expected:
                        raise AssertionError("Resposta inesperada no endpoint " + path)
                    return json.loads(response.read())["dados"]

            accounts = []
            for mode in ("desktop", "mobile", "expired"):
                email = mode + "-" + uuid.uuid4().hex + "@example.test"
                account = api("/auth/cadastro", {"nome": "Pessoa PI170 " + mode, "email": email, "senha": password, "perfil": "comprador"}, 201)
                accounts.append({"email": email, "id": account["id"], "mode": mode})
            expired = accounts[2]
            api("/auth/recuperacao", {"email": expired["email"]}, 201)
            expired_link = None
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                with urllib.request.urlopen(mail_url + "/api/v1/messages", timeout=5) as response:
                    messages = json.load(response)["messages"]
                if messages:
                    with urllib.request.urlopen(mail_url + "/api/v1/message/" + messages[0]["ID"], timeout=5) as response:
                        message = json.load(response)
                    expired_link = next((part for part in message["Text"].split() if part.startswith(base + "/login/reset?token=")), None)
                    if expired_link:
                        break
                time.sleep(0.1)
            if not expired_link:
                raise AssertionError("E-mail SMTP da fixture expirada nao foi recebido.")
            docker("exec", db, "psql", "-U", "pi170", "-d", "pi170", "-Atc",
                "UPDATE password_recovery SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE conta_id='" + expired["id"] + "'")
            run(frontend, "-p", f"127.0.0.1:{frontend_port}:7879", "-e", f"API_GATEWAY_URL=http://{gateway}:8080/api/v1",
                "-e", "AUTH_ALLOWED_ORIGINS=" + base, "balaio/frontend:local")
            wait_url(base + "/healthz")
            front_root = pathlib.Path(__file__).resolve().parents[2] / "Balaio-Front" / "Frontend"
            environment = {**os.environ, "PI170_BASE_URL": base, "PI170_MAILPIT_URL": mail_url,
                "PI170_ACCOUNTS": json.dumps(accounts), "PI170_OLD_PASSWORD": password, "PI170_EXPIRED_LINK": expired_link}
            result = subprocess.run(["node", "tests/pi170-real-browser.cjs"], cwd=front_root, env=environment,
                capture_output=True, text=True, timeout=240)
            if result.stdout.strip():
                print(result.stdout.strip(), flush=True)
            if result.returncode:
                # Não publicar a URL/token de recuperação em mensagens de erro.
                raise RuntimeError("Fluxo de navegador PI-170 falhou; " + result.stderr[-1600:].replace(expired_link, "[REDACTED]"))
            print("PI-170 runtime: SMTP Mailpit, Gateway, Profile, Frontend e Postgres reais aprovados; nenhum envio externo.", flush=True)
        finally:
            for name in reversed(owned):
                subprocess.run(["docker", "rm", "-f", "-v", name], capture_output=True)
            subprocess.run(["docker", "network", "rm", prefix], capture_output=True)


if __name__ == "__main__":
    main()
