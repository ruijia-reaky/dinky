#!/usr/bin/env python3
"""Read-only node log service. No Kubernetes dependency, so deleted Pods remain readable."""
from __future__ import print_function

import errno
import hmac
import json
import os
import re
import stat
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn
from urllib.parse import parse_qs, urlsplit


LOG_NAME = re.compile(r"^.+\.(?:log|out)(?:[.\-][A-Za-z0-9_.-]+)?$")


def safe_path(root, relative, is_file=False):
    """Reject traversal and every symlink component, including links inside the root."""
    if not relative or "\\" in relative or "\x00" in relative or os.path.isabs(relative):
        raise ValueError("Invalid relative path")
    parts = relative.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise ValueError("Invalid path component")
    current = root
    for part in parts:
        current = os.path.join(current, part)
        if os.path.islink(current):
            raise ValueError("Symlinks are not allowed")
    resolved = os.path.realpath(current)
    if not resolved.startswith(root + os.sep):
        raise ValueError("Path outside log root")
    if is_file and not LOG_NAME.match(parts[-1]):
        raise ValueError("Only log and stdout files may be downloaded")
    return resolved


class Handler(BaseHTTPRequestHandler):
    server_version = "DinkyLogAgent/1"

    def do_GET(self):
        if self.path == "/healthz":
            ready = os.path.isdir(self.server.root) and os.access(self.server.root, os.R_OK | os.X_OK)
            self.send_response(200 if ready else 503)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        supplied = self.headers.get("Authorization", "").encode("utf-8")
        expected = ("Bearer " + self.server.token).encode("utf-8")
        if not hmac.compare_digest(supplied, expected):
            self.send_error(401)
            return
        try:
            self.connection.settimeout(30)
            parsed = urlsplit(self.path)
            query = parse_qs(parsed.query, keep_blank_values=True)
            directory = query.get("directory", [""])[0]
            path = safe_path(self.server.root, directory)
            if parsed.path == "/logs":
                self.list_files(path)
            elif parsed.path == "/download":
                name = query.get("file", [""])[0]
                if "/" in name or "\\" in name or "\r" in name or "\n" in name:
                    raise ValueError("Invalid file name")
                self.download(safe_path(self.server.root, directory + "/" + name, True))
            else:
                self.send_error(404)
        except ValueError:
            self.send_error(400)
        except OSError as exc:
            self.send_error(404 if exc.errno in (errno.ENOENT, errno.ENOTDIR) else 403)

    def list_files(self, directory):
        result = []
        for name in sorted(os.listdir(directory)):
            if not LOG_NAME.match(name):
                continue
            path = os.path.join(directory, name)
            details = os.lstat(path)
            if stat.S_ISREG(details.st_mode):
                result.append({"name": name, "size": details.st_size,
                               "modified": int(details.st_mtime * 1000)})
            if len(result) >= 10000:
                break
        payload = json.dumps(result).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def download(self, path):
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
                             | getattr(os, "O_NONBLOCK", 0))
        with os.fdopen(descriptor, "rb") as source:
            # Recheck the opened descriptor to close the parent-symlink race on Linux.
            fd_path = "/proc/self/fd/" + str(source.fileno())
            if os.path.exists("/proc/self/fd"):
                actual = os.path.realpath(fd_path)
                if not actual.startswith(self.server.root + os.sep):
                    raise ValueError("Opened file outside log root")
            details = os.fstat(source.fileno())
            if not stat.S_ISREG(details.st_mode):
                raise ValueError("Not a regular file")
            remaining = details.st_size
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(remaining))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            while remaining:
                data = source.read(min(remaining, 1024 * 1024))
                if not data:
                    break
                self.wfile.write(data)
                remaining -= len(data)

    def log_message(self, fmt, *args):
        # Do not log query parameters, paths, or authentication headers.
        pass


class Server(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, root, token):
        if not token or len(token) < 32:
            raise ValueError("LOG_AGENT_TOKEN must contain at least 32 characters")
        self.root = os.path.realpath(root)
        self.token = token
        self.slots = threading.BoundedSemaphore(32)
        HTTPServer.__init__(self, address, Handler)

    def process_request(self, request, address):
        if not self.slots.acquire(False):
            self.shutdown_request(request)
            return
        try:
            ThreadingMixIn.process_request(self, request, address)
        except Exception:
            self.slots.release()
            raise

    def get_request(self):
        connection, address = HTTPServer.get_request(self)
        connection.settimeout(30)
        return connection, address

    def process_request_thread(self, request, address):
        try:
            ThreadingMixIn.process_request_thread(self, request, address)
        finally:
            self.slots.release()


if __name__ == "__main__":
    Server((os.environ.get("LOG_AGENT_BIND", "127.0.0.1"),
            int(os.environ.get("LOG_AGENT_PORT", "18089"))),
           os.environ.get("LOG_AGENT_ROOT", "/data/flink-logs"),
           os.environ.get("LOG_AGENT_TOKEN", "")).serve_forever()
