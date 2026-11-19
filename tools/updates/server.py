#!/usr/bin/env python3
"""NoMusic's read-only update origin. TLS terminates at the existing OpenResty site."""
import argparse
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


class Handler(BaseHTTPRequestHandler):
    def send_file(self, head=False):
        path = urlsplit(self.path).path
        if path in ("/", "/index.html"):
            target, mime, cache = "index.html", "text/html; charset=utf-8", "no-cache"
        elif path == "/updates/latest.json":
            target, mime, cache = "updates/latest.json", "application/json; charset=utf-8", "no-store"
        elif re.fullmatch(r"/downloads/NoMusic-[0-9]+\.[0-9]+\.[0-9]+-(standard|cs11)\.apk", path):
            target, mime, cache = path[1:], "application/vnd.android.package-archive", "public, max-age=86400"
        else:
            self.send_error(404)
            return
        try:
            with (self.server.directory / target).open("rb") as stream:
                size = os.fstat(stream.fileno()).st_size
                start, end = 0, size - 1
                partial = self.headers.get("Range") if target.endswith(".apk") else None
                if partial:
                    match = re.fullmatch(r"bytes=([0-9]{1,18})-([0-9]{0,18})", partial)
                    if match:
                        start = int(match[1])
                        end = min(end, int(match[2])) if match[2] else end
                    if not match or start > end:
                        self.send_response(416)
                        self.send_header("Content-Range", f"bytes */{size}")
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                self.send_response(206 if partial else 200)
                self.send_header("Content-Type", mime)
                self.send_header("Content-Length", str(end - start + 1))
                self.send_header("Cache-Control", cache)
                self.send_header("X-Content-Type-Options", "nosniff")
                if target.endswith(".apk"):
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header("Content-Disposition", f'attachment; filename="{Path(target).name}"')
                if partial:
                    self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
                self.end_headers()
                if not head:
                    stream.seek(start)
                    remaining = end - start + 1
                    while remaining > 0:
                        data = stream.read(min(remaining, 64 * 1024))
                        if not data:
                            break
                        self.wfile.write(data)
                        remaining -= len(data)
        except FileNotFoundError:
            self.send_error(404)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        self.send_file()

    def do_HEAD(self):
        self.send_file(head=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--port", type=int, default=5202)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.directory = args.directory.resolve()
    server.serve_forever()


if __name__ == "__main__":
    main()
