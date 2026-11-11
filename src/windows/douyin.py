"""抖音聊天页适配层。

核心事实（已在本机实测确认）：
- 入口页：https://www.douyin.com/chat
- 会话项：[data-e2e="conversation-item"]
- 昵称：.conversationConversationItemtitle
- 火花：img.src 末段 normal_couple.png / normal_normal.png（仍在燃烧）
             gray_normal.png / gray_bosom.png（今日未互动，即将熄灭）
- 输入框：[data-e2e="msg-input"]（contenteditable），回车发送
- 会话切换必须用真实鼠标事件（.click() 无效）

据此，自动识别规则：灰色火花 = 今天双方还没互动 = 需要续。
"""
import json
import random
import threading
import time

CHAT_URL = "https://www.douyin.com/chat"

CONV_JS = r"""
(() => {
  const items = [...document.querySelectorAll('[data-e2e="conversation-item"]')];
  const out = [];
  for (const it of items) {
    let nameEl = it.querySelector('.conversationConversationItemtitle');
    if (!nameEl) {
      const w = it.querySelector('[class*="ItemtitleWrapper"]');
      if (w) nameEl = w.firstElementChild;
    }
    const name = nameEl ? (nameEl.innerText || '').trim() : '';
    if (!name) continue;
    // 昵称还没渲染完时会显示成一串 uid，这种占位项直接丢掉，别混进名单
    if (/^[0-9]{5,}$/.test(name)) continue;
    const streakBox = it.querySelector('[class*="streakContainer"]');
    let iconName = '', streakNum = '';
    if (streakBox) {
      const im = streakBox.querySelector('img');
      if (im && im.src) iconName = im.src.split('/').pop().split('?')[0];
      const tn = streakBox.querySelector('[class*="Text"]');
      if (tn) streakNum = (tn.innerText || '').trim();
    }
    const timeEl = it.querySelector('[class*="timeStr"]');
    const descEl = it.querySelector('[class*="ConversationItemDescleft"]');
    let avatar = '';
    for (const im of it.querySelectorAll('img')) {
      const s = im.src || '';
      if (s && !/bytednsdoc|streakicon|flame_icon/i.test(s)) { avatar = s; break; }
    }
    // 群聊没有单人圆形头像容器（commonIMAvataravatarContainer），据此区分
    const isGroup = !it.querySelector('[class*="avatarContainer"], [class*="AvatarContainer"]');
    out.push({
      name: name,
      icon: iconName,
      streak: streakNum,
      time: timeEl ? (timeEl.innerText || '').trim() : '',
      desc: descEl ? (descEl.innerText || '').trim() : '',
      avatar: avatar,
      group: isGroup
    });
  }
  return JSON.stringify(out);
})()
"""

NAME_READY_JS = r"""
(() => {
  const items = [...document.querySelectorAll('[data-e2e="conversation-item"]')];
  if (!items.length) return false;
  let ok = 0;
  for (const it of items) {
    const el = it.querySelector('.conversationConversationItemtitle');
    const t = el ? (el.innerText || '').trim() : '';
    if (t && !/^[0-9]{5,}$/.test(t)) ok++;
  }
  return ok >= Math.min(4, items.length);
})()
"""

HEADER_JS = r"""
(() => {
  const h = document.querySelector('[class*="RightPanelHeaderconvHeader"]')
        || document.querySelector('[class*="RightPanelHeader"]');
  if (!h) return '';
  return (h.innerText || '').trim().split('\n')[0];
})()
"""

STATE_JS = r"""
(() => {
  const n = document.querySelectorAll('[data-e2e="conversation-item"]').length;
  const body = document.body ? (document.body.innerText || '') : '';
  if (body.indexOf('扫码登录') >= 0 && n === 0) return 'guest';
  if (n > 0) return 'user';
  if (body.indexOf('发送消息') >= 0) return 'user';
  return 'loading';
})()
"""


def kind_of(icon):
    """把图标名映射成火花状态：alive / cooling / none。"""
    if not icon:
        return "none"
    if icon.startswith("gray"):
        return "cooling"
    return "alive"


class Douyin:
    def __init__(self, bm, store, log):
        self.bm = bm
        self.store = store
        self.log = log
        self.lock = threading.RLock()
        self._sess = None
        self.last_error = ""

    # ---------------- 会话 ----------------
    def open_chat(self, visible=False, force=False):
        """打开(或复用)聊天页，返回 CDP 会话。"""
        with self.lock:
            if not force and self._sess is not None:
                try:
                    self._sess.eval("1")
                    return self._sess
                except Exception:
                    self._sess = None
            sess = self.bm.open_page(CHAT_URL, visible=visible, timeout=30)
            if not sess:
                self.last_error = "浏览器没有启动起来（调试端口未就绪：多为旧实例占着程序目录，或被杀软拦下）"
                return None
            # 等页面骨架
            state = self._wait(sess, STATE_JS, timeout=20)
            if state == "loading":
                self.bm.goto(sess, CHAT_URL, wait=4)
                state = self._wait(sess, STATE_JS, timeout=20)
            self._sess = sess
            return sess

    def close(self, shutdown_browser=False):
        with self.lock:
            try:
                if self._sess:
                    self._sess.close()
            except Exception:
                pass
            self._sess = None
            if shutdown_browser:
                self.bm.stop()

    def _wait(self, sess, expr, timeout=15, interval=0.4):
        end = time.time() + timeout
        last = None
        while time.time() < end:
            try:
                last = sess.eval(expr)
            except Exception as e:
                self.last_error = str(e)
                return None
            if last and last != "loading":
                return last
            time.sleep(interval)
        return last

    # ---------------- 账号状态 ----------------
    def check_account(self, visible=False):
        """检测登录态。返回 dict。"""
        with self.lock:
            sess = self.open_chat(visible=visible)
            if not sess:
                return {"logged_in": False, "reason": self.last_error or "浏览器不可用"}
            state = self._wait(sess, STATE_JS, timeout=12)
            logged = state == "user"
            info = {"logged_in": logged, "checked_at": int(time.time())}
            if logged:
                info["nickname"] = self._guess_nickname(sess)
            else:
                info["reason"] = "尚未登录"
            return info

    def _guess_nickname(self, sess):
        try:
            v = sess.eval(r"""
(() => {
  const cands = [...document.querySelectorAll('img')].filter(i => {
    const r = i.getBoundingClientRect();
    return r.top < 120 && r.left > window.innerWidth * 0.55 && r.width > 20 && i.src && !/flame_icon|bytednsdoc/i.test(i.src);
  });
  if (cands.length) {
    const p = cands[0].parentElement;
    if (p) {
      const t = (p.innerText || '').trim();
      if (t) return t.split('\n')[0].slice(0, 24);
    }
  }
  return '';
})()
""")
            return v or ""
        except Exception:
            return ""

    # ---------------- 会话列表 ----------------
    def list_conversations(self, sess=None, scroll=True, max_scroll=8):
        own = sess is None
        if own:
            sess = self.open_chat(visible=False)
        if not sess:
            return []
        seen = {}

        def harvest():
            try:
                raw = sess.eval(CONV_JS) or "[]"
                items = json.loads(raw)
            except Exception as e:
                self.last_error = "解析会话列表失败: %s" % e
                return 0
            added = 0
            for it in items:
                k = it.get("name")
                if k and k not in seen:
                    it["kind"] = kind_of(it.get("icon", ""))
                    seen[k] = it
                    added += 1
            return added

        self._wait(sess, STATE_JS, timeout=15)
        # 会话项先于昵称出现，等昵称渲染出来再采，否则会抓到一串 uid 占位
        self._wait(sess, NAME_READY_JS, timeout=14)
        harvest()
        if scroll:
            for _ in range(max_scroll):
                try:
                    sess.call("Input.dispatchMouseEvent", {
                        "type": "mouseWheel", "x": 150, "y": 380,
                        "deltaX": 0, "deltaY": 620, "pointerType": "mouse",
                    }, timeout=8)
                except Exception:
                    break
                time.sleep(0.55)
                if harvest() == 0:
                    break
            try:
                for _ in range(max_scroll + 2):
                    sess.call("Input.dispatchMouseEvent", {
                        "type": "mouseWheel", "x": 150, "y": 380,
                        "deltaX": 0, "deltaY": -640, "pointerType": "mouse",
                    }, timeout=8)
                    time.sleep(0.18)
            except Exception:
                pass
        if own:
            self.close()
        return list(seen.values())

    # ---------------- 会话切换 ----------------
    def _focus_rect(self, sess, name):
        expr = r"""
(() => {
  const target = %s;
  const items = [...document.querySelectorAll('[data-e2e="conversation-item"]')];
  for (const it of items) {
    let nameEl = it.querySelector('.conversationConversationItemtitle');
    if (!nameEl) { const w = it.querySelector('[class*="ItemtitleWrapper"]'); if (w) nameEl = w.firstElementChild; }
    const n = nameEl ? (nameEl.innerText || '').trim() : '';
    if (n !== target) continue;
    try { it.scrollIntoView({block: 'center', behavior: 'instant'}); } catch (e) {}
    const r = it.getBoundingClientRect();
    if (r.height <= 0) continue;
    return JSON.stringify([Math.round(r.left + Math.min(80, r.width / 2)), Math.round(r.top + r.height / 2), Math.round(r.width), Math.round(r.height)]);
  }
  return '';
})()
""" % json.dumps(name)
        for _ in range(2):
            v = sess.eval(expr)
            if v:
                return json.loads(v)
            time.sleep(0.5)
        return None

    def open_conversation(self, sess, name, timeout=6):
        cur = sess.eval(HEADER_JS) or ""
        if cur == name:
            return True
        rect = self._focus_rect(sess, name)
        if not rect:
            self.last_error = "列表里没有找到「%s」" % name
            return False
        x, y = rect[0], rect[1]
        for t in ("mousePressed", "mouseReleased"):
            sess.call("Input.dispatchMouseEvent", {
                "type": t, "x": x, "y": y, "button": "left", "clickCount": 1,
            })
            time.sleep(0.12)
        end = time.time() + timeout
        while time.time() < end:
            cur = sess.eval(HEADER_JS) or ""
            if cur == name:
                time.sleep(0.6)
                return True
            time.sleep(0.3)
        self.last_error = "切换会话超时（当前是「%s」）" % cur
        return False

    # ---------------- 发送 ----------------
    def _editor_rect(self, sess):
        v = sess.eval(r"""
(() => {
  const e = document.querySelector('[data-e2e="msg-input"]') || document.querySelector('[contenteditable="true"]');
  if (!e) return '';
  const r = e.getBoundingClientRect();
  if (r.height <= 0) return '';
  return JSON.stringify([Math.round(r.left + 24), Math.round(r.top + r.height / 2), Math.round(r.width), Math.round(r.height)]);
})()
""")
        return json.loads(v) if v else None

    def _editor_text(self, sess):
        return sess.eval(r"""(() => {
  const e = document.querySelector('[data-e2e="msg-input"]');
  return e ? (e.innerText || '').replace(/\u200b/g, '').trim() : '';
})()""") or ""

    def _clear_editor(self, sess):
        sess.eval(r"""(() => {
  const e = document.querySelector('[data-e2e="msg-input"]');
  if (!e) return 0;
  e.focus();
  try { document.execCommand('selectAll', false, null); document.execCommand('delete', false, null); } catch (err) {}
  return 1;
})()""")

    def _type(self, sess, text):
        """用 execCommand 注入：headless 下比 CDP 的 insertText 更可靠。"""
        return sess.eval(r"""(() => {
  const e = document.querySelector('[data-e2e="msg-input"]');
  if (!e) return 'no-editor';
  e.focus();
  try { document.execCommand('insertText', false, %s); } catch (err) { return 'err:' + err.message; }
  return 'ok';
})()""" % json.dumps(text, ensure_ascii=False))

    def _click_send_button(self, sess):
        """兜底：点输入框右侧的发送按钮。"""
        rect = sess.eval(r"""(() => {
  const ed = document.querySelector('[data-e2e="msg-input"]');
  if (!ed) return '';
  const er = ed.getBoundingClientRect();
  const cands = [...document.querySelectorAll('button, [role="button"], svg, div')].filter(e => {
    const r = e.getBoundingClientRect();
    if (r.width < 18 || r.width > 90 || r.height < 18 || r.height > 90) return false;
    if (Math.abs((r.top + r.height / 2) - (er.top + er.height / 2)) > 45) return false;
    return r.left > er.right - 60 && r.left < er.right + 160;
  }).sort((a, b) => b.getBoundingClientRect().width - a.getBoundingClientRect().width);
  if (!cands.length) return '';
  const r = cands[0].getBoundingClientRect();
  return JSON.stringify([Math.round(r.left + r.width / 2), Math.round(r.top + r.height / 2)]);
})()""")
        if not rect:
            return False
        x, y = json.loads(rect)
        for t in ("mousePressed", "mouseReleased"):
            sess.call("Input.dispatchMouseEvent", {"type": t, "x": x, "y": y, "button": "left", "clickCount": 1})
            time.sleep(0.1)
        time.sleep(1.0)
        return not self._editor_text(sess)

    def send_message(self, sess, name, text):
        """在已打开的会话里发一条文本。返回 (ok, msg)。"""
        if not self._editor_rect(sess):
            return False, "找不到输入框"

        # 清掉可能的草稿
        if self._editor_text(sess):
            self._clear_editor(sess)
            time.sleep(0.3)

        if self._type(sess, text) != "ok":
            return False, "注入失败"
        time.sleep(0.45)
        got = self._editor_text(sess)
        if text not in got:
            return False, "文本没有进入输入框"

        key = {"key": "Enter", "code": "Enter", "windowsVirtualKeyCode": 13, "nativeVirtualKeyCode": 13}
        sess.call("Input.dispatchKeyEvent", dict({"type": "rawKeyDown"}, **key))
        sess.call("Input.dispatchKeyEvent", dict({"type": "char", "text": "\r"}, **key))
        sess.call("Input.dispatchKeyEvent", dict({"type": "keyUp"}, **key))
        time.sleep(1.3)

        if not self._editor_text(sess):
            return True, "已发送"
        # 回车没生效，退回点发送按钮
        if self._click_send_button(sess):
            self._clear_editor(sess)
            return True, "已发送（按钮）"
        return False, "回车后输入框未清空"

    # ---------------- 批量 ----------------
    def run_batch(self, targets, messages, gap=(25, 60), progress=None, sess=None,
                  wait_hook=None, should_stop=None, unique=False):
        """targets: [{"name":...}]，逐个续火花。返回结果列表。
        wait_hook(剩余秒) 让调用方能显示等待倒计时；should_stop() 返回 True 时中止。
        unique=True 时同一批里尽量不重复用同一句文案（用完一轮再重置）。
        """
        results = []
        used = set()
        own = sess is None
        if own:
            sess = self.open_chat(visible=False, force=True)
        if not sess:
            return [{"name": t.get("name"), "ok": False, "msg": self.last_error or "浏览器不可用"} for t in targets]

        joined = False
        try:
            for idx, t in enumerate(targets):
                name = t.get("name", "")
                if should_stop and should_stop():
                    results.append({"name": name, "ok": False, "msg": "已手动停止"})
                    break
                if progress:
                    progress(idx, len(targets), name)
                if not joined:
                    if self._wait(sess, STATE_JS, timeout=15) != "user":
                        results.append({"name": name, "ok": False, "msg": "未登录"})
                        break
                    joined = True
                if not self.open_conversation(sess, name):
                    results.append({"name": name, "ok": False, "msg": self.last_error})
                    continue
                if unique and messages:
                    pool = [m for m in messages if m not in used] or list(messages)
                    text = random.choice(pool)
                    used.add(text)
                    if len(used) >= len(messages) and len(pool) <= 1:
                        used = set([text])
                else:
                    text = random.choice(messages) if messages else "续火花"
                ok, msg = self.send_message(sess, name, text)
                if not ok and "未登录" not in msg and "手动停止" not in msg:
                    # 失败多半是页面抖动：会话没切过去、输入框没就绪。
                    # 停 3 秒重开一次会话再发，仍然失败才认输 —— 比直接记失败靠谱得多。
                    if not (should_stop and should_stop()):
                        time.sleep(3)
                        if self.open_conversation(sess, name):
                            ok2, msg2 = self.send_message(sess, name, text)
                            if ok2:
                                ok, msg = True, msg2 + "（重试成功）"
                            else:
                                msg = msg + "；重试仍失败：" + msg2
                results.append({"name": name, "ok": ok, "msg": msg, "text": text})
                if idx < len(targets) - 1:
                    # 逐秒等待，期间可被停止，也能报倒计时
                    left = int(random.uniform(gap[0], gap[1]))
                    while left > 0:
                        if should_stop and should_stop():
                            return results
                        time.sleep(1)
                        left -= 1
                        if wait_hook:
                            wait_hook(left)
        finally:
            if own:
                self.close(shutdown_browser=True)
        return results
