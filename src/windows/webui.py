"""前端界面。

页面本体在同目录的 app.html（打包时作为数据文件带上），这里只负责读出来。
放在独立文件里是为了能直接写标准 HTML/CSS/JS，改版时不用在 Python 字符串里绕。
"""

import io
import os
import sys


_PATH = os.path.join(getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__))), "app.html")


def _load():
    with io.open(_PATH, encoding="utf-8") as f:
        return f.read()


HTML = _load()
