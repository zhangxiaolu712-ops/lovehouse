"""
LoveHouse Toy MCP Server (本地版)
==================================
装在 Claude Desktop 里，小克直接通过聊天控制玩具，零延迟。

安装: pip install mcp adb-shell
配置: 见下方 Claude Desktop 配置说明
"""

import subprocess
import json
from pathlib import Path
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("lovehouse-toy")

CONFIG_FILE = Path(__file__).parent / "toy_config.json"

DEFAULT_CONFIG = {
    "slider": {
        "x": 540,
        "y_min": 1600,
        "y_max": 800,
    },
}


def load_config():
    if CONFIG_FILE.exists():
        with open(CONFIG_FILE) as f:
            return json.load(f)
    return DEFAULT_CONFIG.copy()


def save_config(cfg):
    with open(CONFIG_FILE, "w") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)


def run_adb(*args):
    cmd = ["adb"] + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
    return r.stdout.strip(), r.returncode


def do_swipe(intensity):
    cfg = load_config()
    slider = cfg["slider"]
    x = slider["x"]
    y_range = slider["y_min"] - slider["y_max"]
    y_target = slider["y_min"] - int(y_range * max(0, min(100, intensity)) / 100)
    y_start = slider["y_min"]
    run_adb("shell", "input", "swipe",
            str(x), str(y_start), str(x), str(y_target), "300")


@mcp.tool()
def control_toy(intensity: int) -> str:
    """控制玩具强度。intensity 范围 0~100，0=关闭，100=最强。
    小克根据聊天语境自行判断合适的强度值，不需要她说具体数字。
    参考: 轻柔=10~25, 温和=30~45, 中等=50~65, 强烈=70~85, 极限=90~100"""
    intensity = max(0, min(100, intensity))
    out, code = run_adb("devices")
    lines = [l for l in out.split("\n")[1:] if l.strip() and "device" in l]
    if not lines:
        return "手机未连接，请先 adb connect"
    do_swipe(intensity)
    if intensity == 0:
        return "已关闭"
    return f"已调到 {intensity}%"


@mcp.tool()
def stop_toy() -> str:
    """立即停止玩具"""
    do_swipe(0)
    return "已停止"


@mcp.tool()
def toy_pattern(pattern: str, duration_seconds: int = 30) -> str:
    """运行一个自动模式。pattern 可选: wave(波浪), heartbeat(心跳), crescendo(渐强)。
    duration_seconds 是持续时间，默认30秒。"""
    import time
    import math

    tick = 0
    end_time = time.time() + duration_seconds

    while time.time() < end_time:
        tick += 1
        if pattern == "wave":
            val = round(30 + 30 * math.sin(tick * 0.3))
        elif pattern == "heartbeat":
            phase = tick % 10
            val = 70 if phase < 2 else 20 if phase < 4 else 85 if phase < 6 else 15
        elif pattern == "crescendo":
            val = min(100, tick * 3)
        else:
            return f"未知模式: {pattern}，可选: wave, heartbeat, crescendo"
        do_swipe(val)
        time.sleep(0.8)

    do_swipe(0)
    return f"{pattern} 模式已完成（{duration_seconds}秒）"


@mcp.tool()
def calibrate_toy(x: int, y_min: int, y_max: int) -> str:
    """校准滑条坐标。x=滑条中心X坐标，y_min=最底部(最弱)Y坐标，y_max=最顶部(最强)Y坐标。
    坐标通过手机「开发者选项→指针位置」获取。"""
    cfg = load_config()
    cfg["slider"] = {"x": x, "y_min": y_min, "y_max": y_max}
    save_config(cfg)
    do_swipe(30)
    return f"已校准: x={x}, y_min={y_min}, y_max={y_max}。测试发送了 30% 强度。"


@mcp.tool()
def check_phone() -> str:
    """检查手机是否通过 ADB 连接"""
    out, code = run_adb("devices")
    if code != 0:
        return "找不到 adb 命令，请确认已安装 Android Platform Tools"
    lines = [l for l in out.split("\n")[1:] if l.strip() and "device" in l]
    if not lines:
        return "没有检测到手机。请先运行: adb connect <手机IP>:<端口>"
    return f"手机已连接: {lines[0].split()[0]}"


if __name__ == "__main__":
    mcp.run()
