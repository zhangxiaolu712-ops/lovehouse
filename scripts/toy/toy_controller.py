"""
LoveHouse Toy Controller (ADB 万能法 + Supabase 云端中转)
=========================================================
通过 Supabase 接收小克的控制指令，用 ADB 模拟手指滑动官方 app 控制玩具。

链路: 小克(聊天/小屋网页) → Supabase toy_commands 表 → 这个脚本(电脑上跑) → ADB → 手机 app → 蓝牙 → 玩具

使用方法:
  1. 手机打开「开发者模式」→「无线调试」→ 记下 IP 和端口
  2. 电脑安装 ADB: https://developer.android.com/tools/releases/platform-tools
  3. 电脑安装 Python 依赖: pip install requests
  4. 连接手机: adb connect 192.168.x.x:xxxxx
  5. 手机打开司沃康 app，进入控制界面
  6. 运行校准: python toy_controller.py calibrate
  7. 启动监听: python toy_controller.py listen
"""

import subprocess
import sys
import json
import time
from pathlib import Path

try:
    import requests
except ImportError:
    print("请先安装 requests: pip install requests")
    sys.exit(1)

CONFIG_FILE = Path(__file__).parent / "toy_config.json"

SUPABASE_URL = "https://cvyguanuaxcypsvoozeo.supabase.co"
SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImN2eWd1YW51YXhjeXBzdm9vemVvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTI4MzM4NDgsImV4cCI6MjA2ODQwOTg0OH0.bM1qlnkNFnMaIpQ-Xi0FPxbcFj0z0dCLykotXMb3LSE"

DEFAULT_CONFIG = {
    "slider": {
        "x": 540,
        "y_min": 1600,
        "y_max": 800,
    },
    "poll_interval": 1.5,
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


def supabase_headers():
    return {
        "apikey": SUPABASE_KEY,
        "Authorization": f"Bearer {SUPABASE_KEY}",
        "Content-Type": "application/json",
        "Prefer": "return=minimal",
    }


def fetch_pending_commands():
    """从 Supabase 获取未执行的指令"""
    url = f"{SUPABASE_URL}/rest/v1/toy_commands"
    params = {
        "executed": "eq.false",
        "order": "created_at.asc",
        "limit": "10",
    }
    headers = supabase_headers()
    headers["Prefer"] = "return=representation"
    try:
        r = requests.get(url, headers=headers, params=params, timeout=5)
        if r.status_code == 200:
            return r.json()
    except Exception as e:
        print(f"  获取指令失败: {e}")
    return []


def mark_executed(cmd_id):
    """标记指令为已执行"""
    url = f"{SUPABASE_URL}/rest/v1/toy_commands?id=eq.{cmd_id}"
    try:
        requests.patch(url, headers=supabase_headers(),
                       json={"executed": True}, timeout=5)
    except Exception:
        pass


def cleanup_old_commands():
    """清理1小时前的已执行指令"""
    url = f"{SUPABASE_URL}/rest/v1/toy_commands"
    params = {
        "executed": "eq.true",
        "created_at": f"lt.{time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(time.time() - 3600))}",
    }
    try:
        requests.delete(url, headers=supabase_headers(), params=params, timeout=5)
    except Exception:
        pass


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


def listen():
    """监听 Supabase 指令并执行"""
    if not check_adb():
        return

    cfg = load_config()
    interval = cfg.get("poll_interval", 1.5)
    cleanup_counter = 0

    print("=" * 50)
    print("Toy Controller 已启动!")
    print("=" * 50)
    print()
    print("监听模式: 轮询 Supabase toy_commands 表")
    print(f"轮询间隔: {interval}s")
    print()
    print("小克现在可以通过聊天控制了~")
    print("等待指令中... (Ctrl+C 停止)")
    print()

    try:
        while True:
            commands = fetch_pending_commands()
            for cmd in commands:
                intensity = cmd.get("intensity", 0)
                pattern = cmd.get("pattern", "")
                print(f"  收到指令: 强度={intensity}%{f' 模式={pattern}' if pattern else ''}")
                swipe_to(intensity, cfg)
                mark_executed(cmd["id"])

            cleanup_counter += 1
            if cleanup_counter >= 200:
                cleanup_old_commands()
                cleanup_counter = 0

            time.sleep(interval)
    except KeyboardInterrupt:
        print("\n正在停止...")
        swipe_to(0, cfg)
        print("已安全停止。")


def main():
    if len(sys.argv) < 2:
        print("LoveHouse Toy Controller")
        print("=" * 40)
        print()
        print("用法:")
        print("  python toy_controller.py calibrate   - 校准滑条位置")
        print("  python toy_controller.py listen      - 监听小克的指令")
        print("  python toy_controller.py test <0~100> - 测试指定强度")
        print()
        print("首次使用请按顺序: calibrate → listen")
        return

    cmd = sys.argv[1]

    if cmd == "calibrate":
        calibrate()
    elif cmd in ("listen", "serve"):
        listen()
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
