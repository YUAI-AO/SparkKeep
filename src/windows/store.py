"""配置与数据存储：单文件 JSON 配置 + 追加式日志，原子写入避免断电损坏。"""
import json
import os
import threading
import time

DEFAULT_CONFIG = {
    "port": 80,
    "enabled": False,
    "wizard_done": False,
    "account": {
        "logged_in": False,
        "nickname": "",
        "unique_id": "",
        "avatar": "",
        "checked_at": 0,
    },
    "schedule": {
        "time": "09:30",
        "jitter_minutes": 45,
        "retry_gap_minutes": 15,
        "max_attempts": 2,
    },
    "friends": [],
    "messages": [
        "早，今天也有好好吃饭吗",
        "打个卡，火花别断了",
        "在忙什么，冒个泡",
        "今天有点冷，记得加件衣服",
        "维持一下，明天见",
    ],
    "appearance": {
        "wallpaper": "",
        "blur": 0,
        "veil": 62,
        "accent": "lime",
        "style": "glass",
        "card_dim": 42,
    },
    "limits": {
        "max_per_day": 1,
        "min_gap_seconds": 2,
        "max_gap_seconds": 5,
    },
    # 自动发送时是否检查"今天已经续过 / 火花已经是亮着的"：
    # True = 只补没续的人；False = 名单里所有人一律再发一遍
    "check_today": True,
    # 开机自启（真实状态以 HKCU Run 键为准，由程序启动时校正）
    "autostart": True,
    # 同一次发送里尽量不重复用同一句文案
    "vary_messages": True,
    # 读会话列表时，把有火花、非群聊的人自动并入名单
    "auto_enroll": True,
}


def _deep_merge(base, patch):
    out = dict(base)
    for k, v in (patch or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _deep_merge(out[k], v)
        else:
            out[k] = v
    return out


class Store:
    def __init__(self, base_dir):
        self.base_dir = base_dir
        self.data_dir = os.path.join(base_dir, "data")
        self.config_path = os.path.join(self.data_dir, "config.json")
        self.log_path = os.path.join(self.data_dir, "history.jsonl")
        self._lock = threading.RLock()
        os.makedirs(self.data_dir, exist_ok=True)
        self.cfg = DEFAULT_CONFIG.copy()
        self.load()

    # ---------- 配置 ----------
    def load(self):
        with self._lock:
            cfg = json.loads(json.dumps(DEFAULT_CONFIG))
            try:
                with open(self.config_path, "r", encoding="utf-8") as f:
                    cfg = _deep_merge(cfg, json.load(f))
            except Exception:
                pass
            self.cfg = cfg
            return cfg

    def save(self):
        with self._lock:
            tmp = self.config_path + ".tmp"
            try:
                with open(tmp, "w", encoding="utf-8") as f:
                    json.dump(self.cfg, f, ensure_ascii=False, indent=2)
                os.replace(tmp, self.config_path)
            except Exception:
                pass

    def patch(self, patch):
        with self._lock:
            self.cfg = _deep_merge(self.cfg, patch)
            self.save()
            return self.cfg

    def get(self):
        with self._lock:
            return json.loads(json.dumps(self.cfg))

    # ---------- 日志 ----------
    def log(self, kind, text, extra=None):
        rec = {"t": int(time.time()), "kind": kind, "text": text}
        if extra:
            rec.update(extra)
        try:
            with self._lock:
                with open(self.log_path, "a", encoding="utf-8") as f:
                    f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                self._rotate()
        except Exception:
            pass
        return rec

    def _rotate(self):
        try:
            if os.path.getsize(self.log_path) > 512 * 1024 and not getattr(self, "_rotating", False):
                self._rotating = True
                os.replace(self.log_path, self.log_path + ".1")
                self._rotating = False
        except Exception:
            self._rotating = False

    def logs(self, limit=80):
        out = []
        try:
            # 只从文件尾部读够 limit 条，不再把整个 512KB 一次 readlines 进内存：
            # 记录页现在是每轮轮询都拉一次，全量读会让这块一直有 IO 抖动。
            size = os.path.getsize(self.log_path)
            window = max(4096, int(limit) * 240)
            start = max(0, size - window)
            with open(self.log_path, "rb") as f:
                f.seek(start)
                blob = f.read()
            lines = blob.decode("utf-8", errors="replace").splitlines()
            if start > 0 and lines:
                lines = lines[1:]        # 首行可能正好被切在中间，丢掉
            for line in lines[-int(limit):]:
                try:
                    out.append(json.loads(line))
                except Exception:
                    continue
        except Exception:
            pass
        return list(reversed(out))
