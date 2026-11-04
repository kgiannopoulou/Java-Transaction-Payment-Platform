"""A merchant's webhook endpoint: accepts everything and reports what it received."""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

received = []


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        received.append({"event": json.loads(body), "attempt": self.headers.get("LedgerForge-Attempt")})
        self.send_response(200)
        self.end_headers()

    def do_GET(self):
        payload = json.dumps(received).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("0.0.0.0", 9000), Handler).serve_forever()
