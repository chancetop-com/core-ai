"""Normalize DeepSeek Harness requests for the core-ai LiteLLM gateway.

The gateway deserializes ``content`` as a list of parts, while dsh sends plain
strings for text messages. This proxy rewrites the request, forwards it, and
streams the response back unchanged.
"""

from __future__ import annotations

import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import requests

UPSTREAM = "https://core-ai-server.internal.connexup-uat.net/api/cli/v1"
FALLBACK_KEY = os.environ.get("DEEPSEEK_API_KEY", "")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            payload = json.dumps(
                {"object": "list", "data": [{"id": "deepseek-flash", "object": "model"}]}
            ).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        self.send_error(404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        try:
            body = json.loads(self.rfile.read(length))
        except (ValueError, OSError) as exc:
            self.send_error(400, str(exc))
            return

        for message in body.get("messages", []):
            content = message.get("content")
            if isinstance(content, str):
                message["content"] = [{"type": "text", "text": content}]
            elif isinstance(content, list):
                message["content"] = [
                    {"type": "text", "text": item} if isinstance(item, str) else item
                    for item in content
                ]

        auth = self.headers.get("Authorization") or f"Bearer {FALLBACK_KEY}"
        headers = {
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            "Authorization": auth,
        }
        try:
            upstream = requests.post(
                UPSTREAM + "/chat/completions",
                data=json.dumps(body).encode(),
                headers=headers,
                stream=True,
                timeout=(15, 1800),
            )
        except Exception as exc:  # noqa: BLE001
            self.send_error(502, str(exc))
            return

        self.send_response(upstream.status_code)
        self.send_header(
            "Content-Type", upstream.headers.get("Content-Type", "text/event-stream")
        )
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        try:
            for chunk in upstream.iter_content(chunk_size=None):
                if not chunk:
                    continue
                self.wfile.write(f"{len(chunk):X}\r\n".encode() + chunk + b"\r\n")
                self.wfile.flush()
        finally:
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", 18080), Handler)
    print("dsh gateway proxy listening on 127.0.0.1:18080", flush=True)
    server.serve_forever()
