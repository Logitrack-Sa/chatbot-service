"""Solo CI: verifica Config Server, Eureka y Prometheus con fixtures HTTP; no llama a IA real."""
import json
import pathlib
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

registered = threading.Event()

class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, payload):
        data = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/eureka/"):
            self.reply({"applications": {"versions__delta": "1", "apps__hashcode": "", "application": []}})
        else:
            self.reply({"name": "chatbot-service", "profiles": ["default"], "propertySources": [
                {"name": "fixture", "source": {"management.metrics.tags.fixture": "ci-config"}}
            ]})

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.path.endswith("/apps/CHATBOT-SERVICE"):
            registered.set()
        self.send_response(204)
        self.end_headers()

    def do_PUT(self):
        self.send_response(200)
        self.end_headers()

    do_DELETE = do_PUT

server = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
threading.Thread(target=server.serve_forever, daemon=True).start()
base = "http://127.0.0.1:" + str(server.server_port)
container = None
try:
    with tempfile.TemporaryDirectory() as directory:
        fixture = pathlib.Path(directory)
        subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
                        "-out", str(fixture / "private.pem")], check=True, capture_output=True)
        subprocess.run(["openssl", "pkey", "-in", str(fixture / "private.pem"), "-pubout",
                        "-out", str(fixture / "public.pem")], check=True, capture_output=True)
        (fixture / "public.pem").chmod(0o644)
        container = subprocess.check_output([
            "docker", "run", "-d", "--network", "host", "--memory", "350m", "--memory-swap", "350m",
            "-e", "CONFIG_SERVER_URL=" + base, "-e", "EUREKA_SERVER_URL=" + base + "/eureka/",
            "-e", "EUREKA_CLIENT_INITIAL_INSTANCE_INFO_REPLICATION_INTERVAL_SECONDS=1",
            "-e", "JWT_PUBLIC_KEY_LOCATION=file:/secrets/public.pem",
            "-e", "AI_API_KEY=ci-fixture-not-a-real-key", "-e", "AI_MODEL=fixture-model",
            "-e", "MANAGEMENT_SERVER_PORT=8094", "-e", "MANAGEMENT_SERVER_ADDRESS=127.0.0.1",
            "-v", str(fixture / "public.pem") + ":/secrets/public.pem:ro", "chatbot-service:ci"
        ], text=True).strip()
        try:
            for _ in range(90):
                try:
                    with urllib.request.urlopen("http://127.0.0.1:8084/actuator/health", timeout=2) as response:
                        if json.load(response)["status"] == "UP":
                            break
                except (OSError, ValueError, KeyError):
                    pass
                time.sleep(1)
            else:
                raise AssertionError("El chatbot no alcanzó estado UP")
            with urllib.request.urlopen("http://127.0.0.1:8094/actuator/prometheus", timeout=3) as response:
                metrics = response.read().decode()
                assert "jvm_memory_used_bytes" in metrics and 'fixture="ci-config"' in metrics
            try:
                urllib.request.urlopen("http://127.0.0.1:8084/actuator/prometheus", timeout=3)
                raise AssertionError("El listener de negocio expuso /actuator/prometheus")
            except urllib.error.HTTPError as failure:
                assert failure.code in (401, 403, 404)
            assert registered.wait(15), "No se registró CHATBOT-SERVICE en Eureka"
            try:
                urllib.request.urlopen("http://127.0.0.1:8084/api/v1/chat/stream", timeout=3)
                raise AssertionError("La ruta protegida aceptó una petición sin JWT")
            except urllib.error.HTTPError as failure:
                assert failure.code == 401
            assert subprocess.check_output(["docker", "exec", container, "id", "-u"], text=True).strip() == "10001"
            print("OK: puerto 8084, Config Server, CHATBOT-SERVICE en Eureka, Prometheus, JWT requerido y usuario no privilegiado")
        except BaseException:
            subprocess.run(["docker", "logs", "--tail", "100", container], check=False)
            raise
finally:
    if container:
        subprocess.run(["docker", "rm", "-f", container], check=False, capture_output=True)
    server.shutdown()
    server.server_close()
