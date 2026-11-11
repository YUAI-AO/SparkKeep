"""极简 Chrome DevTools Protocol 客户端（纯标准库，无第三方依赖）。

用于驱动本机 Edge 浏览器：进程内启动 -> 连接 WebSocket -> 执行 JS / 模拟输入。
体积与内存开销都接近零，适合常驻型小工具。
"""
import base64
import json
import os
import socket
import threading
import time
import urllib.parse
import urllib.request


class WSError(Exception):
    pass


class WebSocket:
    """最小可用的 WebSocket 客户端（RFC6455 文本帧 + 分片 + ping 处理）。"""

    def __init__(self, url, timeout=30):
        u = urllib.parse.urlparse(url)
        self.host = u.hostname
        self.port = u.port or 80
        self.path = (u.path or "/") + (("?" + u.query) if u.query else "")
        self.timeout = timeout
        self.sock = None
        self._buf = b""
        self._closed = False

    def connect(self):
        self.sock = socket.create_connection((self.host, self.port), self.timeout)
        self.sock.settimeout(self.timeout)
        try:
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except Exception:
            pass

        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            "GET %s HTTP/1.1\r\n"
            "Host: %s:%d\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Key: %s\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "\r\n" % (self.path, self.host, self.port, key)
        )
        self.sock.sendall(req.encode("ascii"))

        while b"\r\n\r\n" not in self._buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise WSError("握手阶段连接被关闭")
            self._buf += chunk
        head, self._buf = self._buf.split(b"\r\n\r\n", 1)
        first = head.split(b"\r\n")[0].decode("latin-1", "replace")
        if " 101 " not in first:
            raise WSError("WebSocket 握手失败: %s" % first)
        return self

    # ---------- 底层帧 ----------

    def _recv_exact(self, n):
        while len(self._buf) < n:
            chunk = self.sock.recv(max(4096, n - len(self._buf)))
            if not chunk:
                raise WSError("连接已关闭")
            self._buf += chunk
        out, self._buf = self._buf[:n], self._buf[n:]
        return out

    def _send_frame(self, payload, opcode=0x1):
        b1 = 0x80 | opcode
        n = len(payload)
        if n < 126:
            header = bytes([b1, 0x80 | n])
        elif n < 65536:
            header = bytes([b1, 0x80 | 126]) + n.to_bytes(2, "big")
        else:
            header = bytes([b1, 0x80 | 127]) + n.to_bytes(8, "big")
        mask = os.urandom(4)
        masked = bytes(payload[i] ^ mask[i % 4] for i in range(n))
        self.sock.sendall(header + mask + masked)

    def send_text(self, text):
        self._send_frame(text.encode("utf-8"), 0x1)

    def recv_text(self):
        """读一条完整文本消息，自动处理分片与控制帧。"""
        fragments = []
        while True:
            h = self._recv_exact(2)
            fin = h[0] & 0x80
            opcode = h[0] & 0x0F
            masked = h[1] & 0x80
            length = h[1] & 0x7F
            if length == 126:
                length = int.from_bytes(self._recv_exact(2), "big")
            elif length == 127:
                length = int.from_bytes(self._recv_exact(8), "big")
            mask = self._recv_exact(4) if masked else None
            data = self._recv_exact(length) if length else b""
            if mask:
                data = bytes(data[i] ^ mask[i % 4] for i in range(len(data)))

            if opcode == 0x8:  # close
                self._closed = True
                raise WSError("连接已被对端关闭")
            if opcode == 0x9:  # ping
                self._send_frame(data, 0xA)
                continue
            if opcode == 0xA:  # pong
                continue
            fragments.append(data)
            if fin:
                return b"".join(fragments).decode("utf-8", "replace")

    def close(self):
        try:
            if self.sock:
                try:
                    self._send_frame(b"", 0x8)
                except Exception:
                    pass
                self.sock.close()
        except Exception:
            pass
        self.sock = None
        self._closed = True


class CDPSession:
    """一个页面的 CDP 会话：call(method, params) -> result。"""

    def __init__(self, ws_url, timeout=30):
        self.ws = WebSocket(ws_url, timeout=timeout).connect()
        self._id = 0
        self._lock = threading.Lock()
        self._events = []          # 最近事件，供等待逻辑使用
        self.on_event = None

    def call(self, method, params=None, timeout=None):
        with self._lock:
            self._id += 1
            mid = self._id
            msg = {"id": mid, "method": method, "params": params or {}}
            self.ws.send_text(json.dumps(msg))
            deadline = time.time() + (timeout or self.ws.timeout)
            while True:
                if time.time() > deadline:
                    raise WSError("CDP 调用超时: %s" % method)
                raw = self.ws.recv_text()
                data = json.loads(raw)
                if data.get("id") == mid:
                    if "error" in data:
                        raise WSError("%s -> %s" % (method, data["error"].get("message", "unknown")))
                    return data.get("result", {})
                # 事件：留给等待逻辑
                if "method" in data:
                    self._events.append(data)
                    if len(self._events) > 200:
                        del self._events[:100]
                    if self.on_event:
                        try:
                            self.on_event(data)
                        except Exception:
                            pass

    def eval(self, expression, await_promise=False, timeout=None):
        r = self.call(
            "Runtime.evaluate",
            {"expression": expression, "returnByValue": True, "awaitPromise": await_promise},
            timeout=timeout,
        )
        res = r.get("result", {})
        if r.get("exceptionDetails"):
            raise WSError("页面 JS 异常: %s" % str(r["exceptionDetails"])[:200])
        return res.get("value")

    def wait_event(self, name, timeout=15, since_index=0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            for ev in self._events[since_index:]:
                if ev.get("method") == name:
                    return ev
            time.sleep(0.05)
        return None

    def close(self):
        self.ws.close()


def http_json(url, method="GET", timeout=5):
    req = urllib.request.Request(url, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))
