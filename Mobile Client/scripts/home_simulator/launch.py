"""Windows double-click launcher for the existing AuraFlow development environment."""
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import ProxyHandler, build_opener

CLIENT = Path(__file__).resolve().parents[2]
STATE = Path(os.environ["LOCALAPPDATA"]) / "ScreenMind" / "home-simulator"
STUDIO = "http://127.0.0.1:8766"
PACKAGE = "cn.weiguang.app"
AVD = "ScreenMind35"
HTTP = build_opener(ProxyHandler({}))


def port_open(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=.4):
            return True
    except OSError:
        return False


def service_ready(url, check):
    try:
        with HTTP.open(url, timeout=2) as response:
            return check(json.load(response))
    except (URLError, OSError, ValueError, TypeError, KeyError):
        return False


def start_service(name, port, path, check, args, env):
    url = f"http://127.0.0.1:{port}{path}"
    if service_ready(url, check):
        print(f"{name} 已就绪，复用端口 {port}。")
        return
    if port_open(port):
        raise RuntimeError(f"端口 {port} 已被占用，当前服务未提供所需接口。")
    log = STATE / f"{name}.log"
    with log.open("ab") as stream:
        process = subprocess.Popen([sys.executable, "-m", *args], cwd=CLIENT, env=env, stdin=subprocess.DEVNULL,
                                   stdout=stream, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
    deadline = time.monotonic() + 50
    while time.monotonic() < deadline and process.poll() is None:
        if service_ready(url, check):
            return
        time.sleep(.5)
    raise RuntimeError(f"{name} 启动失败，请查看 {log}")


def android_tools():
    props = CLIENT / "local.properties"
    configured = None
    if props.is_file():
        for line in props.read_text(encoding="utf-8-sig").splitlines():
            key, _, value = line.partition("=")
            if key.strip() == "sdk.dir":
                configured = value.strip().replace(r"\:", ":").replace("\\\\", "\\")
                break
    candidates = [configured, os.getenv("ANDROID_HOME"), os.getenv("ANDROID_SDK_ROOT"),
                  CLIENT / ".tools/sdk", Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"]
    adb_on_path = shutil.which("adb")
    if adb_on_path:
        candidates.append(Path(adb_on_path).resolve().parents[1])
    for candidate in filter(None, candidates):
        sdk = Path(candidate)
        if (sdk / "emulator/emulator.exe").is_file() and (sdk / "platform-tools/adb.exe").is_file():
            break
    else:
        raise RuntimeError("未找到 Android SDK。请检查 Mobile Client/local.properties 的 sdk.dir。")
    avd_paths = [os.getenv("ANDROID_AVD_HOME"), sdk.parent / "avd",
                 Path(os.getenv("ANDROID_USER_HOME") or Path.home() / ".android") / "avd"]
    for path in filter(None, avd_paths):
        if (Path(path) / f"{AVD}.ini").is_file():
            return sdk, Path(path)
    raise RuntimeError(f"未找到 {AVD}。请将 ANDROID_AVD_HOME 指向包含 {AVD}.ini 的目录。")


def adb(sdk, *args, check=True, timeout=30):
    proc = subprocess.run([sdk / "platform-tools/adb.exe", *args], capture_output=True, timeout=timeout,
                          encoding="utf-8", errors="replace", creationflags=subprocess.CREATE_NO_WINDOW)
    if check and proc.returncode:
        raise RuntimeError((proc.stderr or proc.stdout).strip() or "ADB 命令执行失败")
    return proc.stdout.strip()


def start_phone(sdk, env):
    adb(sdk, "start-server")
    serial = None
    for line in adb(sdk, "devices").splitlines():
        fields = line.split()
        if len(fields) == 2 and fields[0].startswith("emulator-") and fields[1] == "device":
            if AVD in adb(sdk, "-s", fields[0], "emu", "avd", "name", check=False).splitlines():
                serial = fields[0]
                break
    process = None
    if serial is None:
        port = next((p for p in range(5574, 5683, 2) if not port_open(p) and not port_open(p+1)), None)
        if port is None:
            raise RuntimeError("没有可用的模拟器端口。")
        serial = f"emulator-{port}"
        log = STATE / "desktop-emulator.out.log"
        with log.open("ab") as stream:
            process = subprocess.Popen([sdk / "emulator/emulator.exe", "-avd", AVD, "-port", str(port),
                                        "-no-snapshot", "-no-audio", "-no-boot-anim", "-gpu", "swiftshader", "-memory", "2048"],
                                       cwd=sdk / "emulator", env=env, stdin=subprocess.DEVNULL,
                                       stdout=stream, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
    print(f"正在打开手机 {AVD}（{serial}）…")
    deadline = time.monotonic() + 240
    while adb(sdk, "-s", serial, "shell", "getprop", "sys.boot_completed", check=False) != "1":
        if time.monotonic() > deadline or (process and process.poll() not in (None, 0)):
            raise RuntimeError(f"模拟器未完成启动，请查看 {STATE / 'desktop-emulator.out.log'}")
        time.sleep(1)
    if AVD not in adb(sdk, "-s", serial, "emu", "avd", "name").splitlines():
        raise RuntimeError(f"{serial} 对应的虚拟设备已变化。")
    adb(sdk, "-s", serial, "reverse", "tcp:8765", "tcp:8765")
    return serial


def open_home(sdk, serial):
    apk = CLIENT / "app/build/outputs/apk/debug/app-debug.apk"
    installed = adb(sdk, "-s", serial, "shell", "pm", "path", PACKAGE, check=False)
    base = next((line[8:] for line in installed.splitlines() if line.startswith("package:") and line.endswith("/base.apk")), "")
    if apk.is_file():
        device_hash = adb(sdk, "-s", serial, "shell", "sha256sum", base, check=False).split() if base else []
        with apk.open("rb") as stream:
            local_hash = hashlib.file_digest(stream, "sha256").hexdigest()
        if device_hash[:1] != [local_hash]:
            print("正在安装本地调试 APK…")
            adb(sdk, "-s", serial, "install", "-r", str(apk), timeout=180)
    elif not base:
        raise RuntimeError(f"模拟器尚未安装微光，请构建 :app:assembleDebug 生成 {apk}。")
    adb(sdk, "-s", serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb(sdk, "-s", serial, "shell", "wm", "dismiss-keyguard")
    result = adb(sdk, "-s", serial, "shell", "am", "start", "-W", "--activity-clear-top", "-n", PACKAGE + "/.MainActivity")
    if "Status: ok" not in result:
        raise RuntimeError("打开手机主页失败：" + result)


def show_workspace(serial):
    # ShellExecute opens the user's default browser. Move only the simulator and its named console.
    ui = ctypes.WinDLL("user32", use_last_error=True)
    ui.SetProcessDPIAware()
    phone = None
    browser = None

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def locate(hwnd, _):
        nonlocal phone, browser
        if ui.IsWindowVisible(hwnd):
            title = ctypes.create_unicode_buffer(512)
            ui.GetWindowTextW(hwnd, title, len(title))
            if title.value == f"Android Emulator - {AVD}:{serial.split('-')[-1]}":
                phone = hwnd
            elif title.value.startswith("微光 · 首页事件推演"):
                browser = hwnd
        return True

    ui.EnumWindows(locate, 0)
    if not browser:
        os.startfile(STUDIO + "/")
    deadline = time.monotonic() + 7
    while not (phone and browser) and time.monotonic() < deadline:
        time.sleep(.25)
        ui.EnumWindows(locate, 0)
    if not phone:
        raise RuntimeError(f"设备 {serial} 已连接，但未找到 {AVD} 的可见窗口。请检查该设备是否以无窗口模式运行。")
    if phone and browser:
        area, rect = wintypes.RECT(), wintypes.RECT()
        ui.SystemParametersInfoW(0x30, 0, ctypes.byref(area), 0)  # SPI_GETWORKAREA
        ui.ShowWindow(browser, 9)  # SW_RESTORE
        if ui.IsIconic(phone):
            ui.ShowWindow(phone, 9)
        ui.GetWindowRect(phone, ctypes.byref(rect))
        width, height = rect.right - rect.left, rect.bottom - rect.top
        split = area.left + width + 88  # leave room for the emulator toolbar
        if area.right - split > 400:
            ui.SetWindowPos(browser, 0, split, area.top+8, area.right-split-8, area.bottom-area.top-16, 0x10)
            ui.SetWindowPos(phone, 0, area.left+8, max(area.top, (area.bottom+area.top-height)//2), width, height, 0x10)
        ui.SetForegroundWindow(browser)


def main():
    import msvcrt
    if sys.version_info < (3, 12):
        raise RuntimeError("请使用 Python 3.12 或更新版本启动 ScreenMind。")
    STATE.mkdir(parents=True, exist_ok=True)
    with (STATE / "launch.lock").open("a+b") as lock:
        lock.seek(0)
        try:
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            print("启动正在进行，请稍候。")
            return
        sdk, avd_home = android_tools()
        env = dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk), ANDROID_AVD_HOME=str(avd_home),
                   PYTHONPATH=str(CLIENT.parent / "Cloud Agent"), WEIGUANG_DB=str(STATE / "gateway.sqlite3"),
                   WEIGUANG_AGENT_ROOT=str(STATE / "agents"), SCREENMIND_PROVIDER="mock", SCREENMIND_MEMORY_MODE="basic",
                   SCREENMIND_MEMORY_EXTRACTOR="raw", SCREENMIND_MEMORY_PLANNING="false",
                   SCREENMIND_EVIDENCE_REVIEW="false", SCREENMIND_EMBEDDING="hash-demo", PYTHONUTF8="1")
        print("正在连接本地网关和模拟输入控制台…")
        start_service("gateway", 8765, "/openapi.json", lambda doc: "/api/v1/home/timeline" in doc["paths"],
                      ["uvicorn", "backend.main:app", "--host", "127.0.0.1", "--port", "8765"], env)
        start_service("studio", 8766, "/api/catalog", lambda rows: any(r["id"] == "overview" for r in rows),
                      ["scripts.home_simulator.server"], env)
        serial = start_phone(sdk, env)
        open_home(sdk, serial)
        show_workspace(serial)
        print(f"手机主页与控制台已打开。{STUDIO}/")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"启动未完成：{error}", file=sys.stderr)
        sys.exit(1)
