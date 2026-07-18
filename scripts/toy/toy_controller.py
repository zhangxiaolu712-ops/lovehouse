"""
LoveHouse Toy Controller (ADB 万能法)
======================================
通过 ADB 模拟手指滑动官方 app 的控制滑条来控制玩具。

链路: 小克(AI) → 这个脚本(电脑上跑) → ADB(WiFi) → 手机官方 app → 蓝牙 → 玩具

使用方法:
  1. 手机打开「开发者模式」→「无线调试」→ 记下 IP 和端口
  2. 电脑安装 ADB: https://developer.android.com/tools/releases/platform-tools
  3. 连接手机: adb connect 192.168.x.x:xxxxx
  4. 手机打开司沃康 app，进入控制界面
  5. 运行校准: python toy_controller.py calibrate
  6. 启动服务: python toy_controller.py serve
"""

import subprocess
import sys
import json
import os
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path

CONFIG_FILE = Path(__file__).parent / "toy_config.json"

DEFAULT_CONFIG = {
    "phone_ip": "",
    "slider": {
        "x": 540,
        "y_min": 1600,
        "y_max": 800,
    },
    "port": 8269,
}


def load_config():
    if CONFIG_FILE.exists():
        with open(CONFIG_FILE) as f:
            return json.load(f)
    return DEFAULT_CONFIG.copy()


def save_config(cfg):
    with open(CONFIG_FILE, "w") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)
    print(f"配置已保存到 {CONFIG_FILE}")


def adb(*args):
    cmd = ["adb"] + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
    return r.stdout.strip(), r.returncode


def check_adb():
    out, code = adb("devices")
    if code != 0:
        print("找不到 adb，请先安装 Android Platform Tools")
        print("下载地址: https://developer.android.com/tools/releases/platform-tools")
        return False
    lines = [l for l in out.split("\n")[1:] if l.strip() and "device" in l]
    if not lines:
        print("没有检测到已连接的手机。")
        print("请先执行: adb connect <手机IP>:<端口>")
        print("手机上: 设置 → 开发者选项 → 无线调试 → 查看 IP 和端口")
        return False
    print(f"已连接设备: {lines[0].split()[0]}")
    return True


def swipe_to(intensity, cfg):
    """intensity: 0~100, 0=关闭, 100=最强"""
    slider = cfg["slider"]
    x = slider["x"]
    y_range = slider["y_min"] - slider["y_max"]
    y_target = slider["y_min"] - int(y_range * intensity / 100)
    y_current = slider["y_min"]
    adb("shell", "input", "swipe",
        str(x), str(y_current), str(x), str(y_target), "300")
    return y_target


def tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def calibrate():
    """交互式校准: 找到 app 里滑条的位置"""
    print("=" * 50)
    print("滑条校准向导")
    print("=" * 50)
    print()
    print("请先在手机上打开司沃康 app，进入玩具控制界面。")
    print("确保滑条可见。")
    print()

    if not check_adb():
        return

    cfg = load_config()

    print()
    print("接下来需要你找到滑条的坐标。")
    print("方法: 手机「开发者选项」→ 打开「指针位置」→ 手指触摸滑条看坐标")
    print()

    x = input(f"滑条中心的 X 坐标 (默认 {cfg['slider']['x']}): ").strip()
    if x:
        cfg["slider"]["x"] = int(x)

    y_min = input(f"滑条最底部(最弱)的 Y 坐标 (默认 {cfg['slider']['y_min']}): ").strip()
    if y_min:
        cfg["slider"]["y_min"] = int(y_min)

    y_max = input(f"滑条最顶部(最强)的 Y 坐标 (默认 {cfg['slider']['y_max']}): ").strip()
    if y_max:
        cfg["slider"]["y_max"] = int(y_max)

    save_config(cfg)

    print()
    print("测试一下? 发送 30% 强度...")
    swipe_to(30, cfg)
    print("如果玩具有反应，校准成功!")
    print("如果没反应，重新运行 calibrate 调整坐标。")


class ToyHandler(BaseHTTPRequestHandler):
    config = None

    def do_POST(self):
        if self.path == "/control":
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length)) if length else {}
            intensity = max(0, min(100, int(body.get("intensity", 0))))
            swipe_to(intensity, self.config)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps({"ok": True, "intensity": intensity}).encode())
        elif self.path == "/stop":
            swipe_to(0, self.config)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps({"ok": True, "stopped": True}).encode())
        else:
            self.send_error(404)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def log_message(self, fmt, *args):
        print(f"  [{self.log_date_time_string()}] {fmt % args}")


def serve():
    """启动 HTTP 服务，等待 AI 发来控制指令"""
    if not check_adb():
        return

    cfg = load_config()
    port = cfg.get("port", 8269)

    ToyHandler.config = cfg
    server = HTTPServer(("0.0.0.0", port), ToyHandler)

    print("=" * 50)
    print(f"Toy Controller 已启动!")
    print(f"监听端口: {port}")
    print("=" * 50)
    print()
    print("接口:")
    print(f"  POST http://localhost:{port}/control")
    print(f"    body: {{\"intensity\": 0~100}}")
    print(f"  POST http://localhost:{port}/stop")
    print()
    print("等待指令中... (Ctrl+C 停止)")
    print()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")
        swipe_to(0, cfg)
        server.server_close()


def main():
    if len(sys.argv) < 2:
        print("用法:")
        print("  python toy_controller.py calibrate  - 校准滑条位置")
        print("  python toy_controller.py serve      - 启动控制服务")
        print("  python toy_controller.py test <0~100> - 测试指定强度")
        return

    cmd = sys.argv[1]

    if cmd == "calibrate":
        calibrate()
    elif cmd == "serve":
        serve()
    elif cmd == "test":
        if not check_adb():
            return
        cfg = load_config()
        val = int(sys.argv[2]) if len(sys.argv) > 2 else 50
        print(f"发送强度: {val}%")
        swipe_to(val, cfg)
        print("已发送!")
    else:
        print(f"未知命令: {cmd}")


if __name__ == "__main__":
    main()
