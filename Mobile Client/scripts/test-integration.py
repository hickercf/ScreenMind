"""Run real emulator OCR, an actual connection outage, retry, and UI confirmation.

Requires a dedicated mock gateway mapped by adb reverse tcp:8765 tcp:8876.
No production accounts or real chat apps are used.
"""
import argparse,json,subprocess,time,hashlib
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def run(args):
    adb=str(ROOT/'.tools/sdk/platform-tools/adb.exe')
    out=ROOT/'artifacts/emulator-integration';out.mkdir(parents=True,exist_ok=True)
    command=[adb,'-s',args.serial]
    def call(*tail):
        return subprocess.run(command+list(tail),check=True,capture_output=True,text=True,encoding='utf-8',errors='replace').stdout
    call('reverse','tcp:8765','tcp:8876')
    report={'synthetic':True,'serial':args.serial,'provider':'mock','passed':False,'stages':[]}
    started=time.monotonic()
    process=subprocess.Popen(command+['shell','am','instrument','-w','-r','-e','class',
        'cn.weiguang.app.IntegratedCaptureTest','-e','managedCaptureTest','true','cn.weiguang.app.test/androidx.test.runner.AndroidJUnitRunner'],
        stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1)
    lines=[]
    try:
        for line in process.stdout:
            lines.append(line);print(line.rstrip(),flush=True)
            if 'integration_stage=disconnect' in line:
                call('reverse','--remove','tcp:8765');report['stages'].append('actual_adb_forward_removed')
            elif 'integration_stage=reconnect' in line:
                call('reverse','tcp:8765','tcp:8876');report['stages'].append('actual_adb_forward_restored')
            elif 'integration_stage=confirmed' in line:
                report['stages'].append('state_version_1_confirmed_in_ui')
        process.wait()
        report['passed']=process.returncode==0 and 'OK (1 test)' in ''.join(lines) and len(report['stages'])==3
        report['seconds']=round(time.monotonic()-started,2)
        screenshots=out/'screenshots';screenshots.mkdir(exist_ok=True)
        call('pull','/sdcard/Android/data/cn.weiguang.app/files/screenshots/.',str(screenshots))
        report['screenshots']=[{'name':p.name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in screenshots.glob('integrated-*.png')]
    finally:
        call('reverse','tcp:8765','tcp:8876')
        (out/'device-output.txt').write_text(''.join(lines),encoding='utf-8')
        (out/'device-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report),flush=True)
    if not report['passed']:raise SystemExit(1)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--serial',default='emulator-5560')
    run(parser.parse_args())
