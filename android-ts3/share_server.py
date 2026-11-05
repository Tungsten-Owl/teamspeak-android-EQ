#!/usr/bin/env python3
"""简单的文件分享服务器：分享 android\\app\\build\\outputs\\apk\\debug 文件夹中的任意文件。

启动：
    python share_server.py
然后在浏览器访问 http://127.0.0.1:8000/ 即可看到文件列表并下载。

安全（H10 修复）：
- 默认绑定 0.0.0.0（所有网卡，局域网/公网均可见）；
- 如需仅本机访问，用 `--host 127.0.0.1` 显式限制，
  但请注意该目录无任何鉴权，任何能访问到该端口的人都能下载/枚举内容，
  用完请立即关闭。
"""

import argparse
import os
import socket
import html
import datetime
import urllib.parse
from http.server import HTTPServer, SimpleHTTPRequestHandler

BASE_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "dist",
)
HOST = "0.0.0.0"
PORT = 8000


def human_size(num):
    """将字节数转换为可读大小。"""
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if num < 1024 or unit == "TB":
            return f"{num:.1f} {unit}" if unit != "B" else f"{num} B"
        num /= 1024


def get_lan_ips():
    """获取本机局域网 IP，便于其他设备访问。"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return [ip]
    except Exception:
        return []


class ShareHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=BASE_DIR, **kwargs)

    def _safe_path(self, url_path):
        """将 URL 路径映射到 file 目录内的真实路径，防止路径穿越。"""
        rel = urllib.parse.unquote(url_path).lstrip("/")
        full = os.path.realpath(os.path.join(BASE_DIR, rel))
        if not full.startswith(os.path.realpath(BASE_DIR) + os.sep):
            return None
        return full

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path in ("/", "/index.html"):
            self.serve_index()
        else:
            self.send_download(parsed.path)

    def serve_index(self):
        """生成文件列表页面。"""
        items = []
        for name in sorted(os.listdir(BASE_DIR)):
            full = os.path.join(BASE_DIR, name)
            if os.path.isfile(full):
                size = human_size(os.path.getsize(full))
                mtime = datetime.datetime.fromtimestamp(
                    os.path.getmtime(full)
                ).strftime("%Y-%m-%d %H:%M")
                items.append((name, size, mtime))

        rows = "\n".join(
            f'<tr>'
            f'<td><a href="{urllib.parse.quote(name)}" download>{html.escape(name)}</a></td>'
            f'<td>{size}</td>'
            f'<td>{mtime}</td>'
            f'</tr>'
            for name, size, mtime in items
        )
        empty_row = (
            '<tr><td colspan="3" class="empty">'
            + BASE_DIR
            + ' 文件夹为空，放入文件后刷新即可</td></tr>'
        )
        page = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>文件分享</title>
<style>
  body {{ font-family: "Microsoft YaHei", Arial, sans-serif; max-width: 720px; margin: 40px auto; padding: 0 16px; color: #333; }}
  h1 {{ font-size: 20px; }}
  table {{ width: 100%; border-collapse: collapse; }}
  th, td {{ text-align: left; padding: 10px 12px; border-bottom: 1px solid #eee; }}
  th {{ color: #888; font-weight: normal; font-size: 13px; }}
  a {{ color: #1677ff; text-decoration: none; word-break: break-all; }}
  a:hover {{ text-decoration: underline; }}
  .size, .time {{ color: #999; font-size: 13px; white-space: nowrap; }}
  .empty {{ color: #999; padding: 30px 0; text-align: center; }}
</style>
</head>
<body>
<h1>📁 文件分享</h1>
<table>
  <tr><th>文件名</th><th>大小</th><th>修改时间</th></tr>
  {rows if rows else empty_row}
</table>
</body>
</html>
"""
        data = page.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_download(self, url_path):
        """发送文件下载响应。"""
        full = self._safe_path(url_path)
        if not full or not os.path.isfile(full):
            self.send_error(404, "Not Found")
            return
        filename = os.path.basename(full)
        size = os.path.getsize(full)
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header(
            "Content-Disposition",
            f"attachment; filename*=UTF-8''{urllib.parse.quote(filename)}",
        )
        self.send_header("Content-Length", str(size))
        self.end_headers()
        try:
            with open(full, "rb") as f:
                self.copyfile(f, self.wfile)
        except (ConnectionResetError, BrokenPipeError):
            # 客户端中途断开（如取消下载/关闭页面），属正常情况，静默处理
            pass


def main():
    global HOST
    parser = argparse.ArgumentParser(description="本地文件分享服务器（默认所有网卡可访问）")
    parser.add_argument("--host", default=HOST,
                        help="绑定地址；默认 0.0.0.0（局域网可见，无鉴权，用完即关）。仅本机请用 127.0.0.1")
    parser.add_argument("--port", type=int, default=PORT)
    args = parser.parse_args()
    HOST = args.host
    os.makedirs(BASE_DIR, exist_ok=True)
    server = HTTPServer((HOST, args.port), ShareHandler)
    print("=" * 50)
    print(f"  文件分享服务器已启动")
    print(f"  本机访问:   http://127.0.0.1:{args.port}/")
    for ip in get_lan_ips():
        if HOST == "0.0.0.0":
            print(f"  局域网访问: http://{ip}:{args.port}/  （无鉴权，注意关闭）")
    print(f"  分享目录:   {BASE_DIR}")
    print(f"  按 Ctrl+C 停止服务")
    print("=" * 50)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n服务已停止")
        server.server_close()


if __name__ == "__main__":
    main()
