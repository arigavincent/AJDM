#!/usr/bin/env python3
"""Range-capable static file server for the AJDM network lab.

Optional faults model common real-world hosts:
  --per-conn-rate BYTES   pace each TCP connection (file hosts/CDNs that throttle per connection)
  --drop-prob P           abort a response mid-body with probability P (flaky mobile paths, proxies)
  --max-conns N           refuse (HTTP 503) beyond N concurrent responses (hosts that cap connections)
"""
import argparse
import os
import random
import re
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CHUNK = 16 * 1024


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "AJDMLab/1"

    def log_message(self, *_):
        pass

    def do_HEAD(self):
        self._serve(head=True)

    def do_GET(self):
        self._serve(head=False)

    def _serve(self, head):
        cfg = self.server.cfg
        size = cfg["size"]
        start, end, status = 0, size - 1, 200
        header = self.headers.get("Range")
        if header:
            match = re.fullmatch(r"bytes=(\d*)-(\d*)", header.strip())
            if not match or (not match[1] and not match[2]):
                self.send_error(416)
                return
            if match[1]:
                start = int(match[1])
                end = int(match[2]) if match[2] else size - 1
            else:
                start = max(0, size - int(match[2]))
            end = min(end, size - 1)
            if start > end:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            status = 206
        limit = cfg["max_conns"]
        if not head and limit and not self.server.slots.acquire(blocking=False):
            self.send_response(503)
            self.send_header("Retry-After", "1")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        try:
            self.send_response(status)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("ETag", cfg["etag"])
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(end - start + 1))
            if status == 206:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            if head:
                return
            self._body(start, end)
        finally:
            if not head and limit:
                self.server.slots.release()

    def _body(self, start, end):
        cfg = self.server.cfg
        rate = cfg["per_conn_rate"]
        remaining = end - start + 1
        drop_at = -1
        if cfg["drop_prob"] and random.random() < cfg["drop_prob"] and remaining > CHUNK:
            drop_at = random.randint(CHUNK, remaining - 1)
        sent = 0
        began = time.monotonic()
        with open(cfg["path"], "rb") as source:
            source.seek(start)
            while remaining > 0:
                block = source.read(min(CHUNK, remaining))
                if drop_at >= 0 and sent + len(block) > drop_at:
                    self.wfile.write(block[: drop_at - sent])
                    self.wfile.flush()
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.close_connection = True
                    return
                self.wfile.write(block)
                sent += len(block)
                remaining -= len(block)
                if rate:
                    ahead = sent / rate - (time.monotonic() - began)
                    if ahead > 0:
                        time.sleep(ahead)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 128

    def handle_error(self, request, client_address):
        pass  # Clients closing segments early is normal for download managers.


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("file")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--per-conn-rate", type=int, default=0)
    parser.add_argument("--drop-prob", type=float, default=0.0)
    parser.add_argument("--max-conns", type=int, default=0)
    args = parser.parse_args()
    stat = os.stat(args.file)
    server = Server(("127.0.0.1", args.port), Handler)
    server.cfg = {
        "path": args.file, "size": stat.st_size, "etag": f'"{stat.st_size:x}-{int(stat.st_mtime):x}"',
        "per_conn_rate": args.per_conn_rate, "drop_prob": args.drop_prob, "max_conns": args.max_conns,
    }
    server.slots = threading.BoundedSemaphore(args.max_conns or 1)
    print("READY", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
