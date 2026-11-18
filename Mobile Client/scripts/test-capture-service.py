"""Service-level QA on the dedicated emulator with a separate synthetic chat application.

Requires app/test/fixture debug APKs and a mock gateway on localhost:8876.
Temporarily enables only our accessibility service on the test emulator, then restores settings.
"""
import argparse
import json
import subprocess
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVICE = 'cn.weiguang.app/cn.weiguang.app.capture.ChatCaptureService'


def run(serial, out):
    if not serial.startswith('emulator-'):
        raise SystemExit('This runner is restricted to synthetic emulator testing.')
    adb = [str(ROOT / '.tools/sdk/platform-tools/adb.exe'), '-s', serial]
    out.mkdir(parents=True, exist_ok=True)

    def call(*args):
        return subprocess.run(adb + list(args), check=True, capture_output=True,
                              text=True, encoding='utf-8', errors='replace').stdout.strip()

    for apk in ('app/build/outputs/apk/debug/app-debug.apk',
                'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
                'capturefixture/build/outputs/apk/debug/capturefixture-debug.apk'):
        print(call('install', '-r', str(ROOT / apk)), flush=True)

    prior_services = call('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
    prior_enabled = call('shell', 'settings', 'get', 'secure', 'accessibility_enabled')
    old_forward = next((line.split()[-1] for line in call('reverse', '--list').splitlines()
                        if len(line.split()) == 3 and line.split()[1] == 'tcp:8765'), None)
    services = [] if prior_services in ('', 'null') else prior_services.split(':')
    if SERVICE not in services:
        services.append(SERVICE)
    output = ''
    try:
        call('reverse', 'tcp:8765', 'tcp:8876')
        # Instrumentation force-stops the target process. Bind only after its UiAutomation is ready.
        others = ':'.join(s for s in services if s != SERVICE)
        if others:
            call('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', others)
        else:
            call('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services')
        process = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'cn.weiguang.app.CaptureServiceFlowTest', '-e', 'managedServiceCaptureTest', 'true',
            'cn.weiguang.app.test/androidx.test.runner.AndroidJUnitRunner'],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', bufsize=1)
        lines = []
        watchdog = threading.Timer(240, process.kill)
        watchdog.start()
        try:
            for line in process.stdout:
                lines.append(line)
                print(line.rstrip(), flush=True)
                if 'capture_stage=enable_service' in line:
                    call('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(services))
                    call('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
        finally:
            watchdog.cancel()
            output = ''.join(lines)
        process.wait(timeout=240)
        call('pull', '/sdcard/Android/data/cn.weiguang.app/files/capture-service/.', str(out))
        report = json.loads((out / 'report.json').read_text(encoding='utf-8'))
        if process.returncode or 'OK (1 test)' not in output or not report['passed']:
            raise SystemExit('Service-level capture test failed; see artifacts/capture-service.')
        print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    finally:
        (out / 'device-output.txt').write_text(output, encoding='utf-8')
        (out / 'logcat.txt').write_text(call('logcat', '-d', '-t', '1500'), encoding='utf-8')
        for key, original in (('enabled_accessibility_services', prior_services), ('accessibility_enabled', prior_enabled)):
            if original in ('', 'null'):
                call('shell', 'settings', 'delete', 'secure', key)
            else:
                call('shell', 'settings', 'put', 'secure', key, original)
        if old_forward:
            call('reverse', 'tcp:8765', old_forward)
        else:
            call('reverse', '--remove', 'tcp:8765')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', default='emulator-5560')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/capture-service')
    args = parser.parse_args()
    run(args.serial, args.output_dir)
