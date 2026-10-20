"""HB RetroArch(코어 브릿지를 넣은 RetroArch 안드로이드) 빌드.

    python haebang/build.py                # assets 준비 → 서명 열쇠(없으면 만듦) → assembleHaebangRelease → haebang/out/
    python haebang/build.py --skip-assets  # assets 는 이미 풀어 둔 것을 씀

공식 CI 와 같은 도구를 쓴다: JDK 11 · Gradle 6.7.1(wrapper) · AGP 4.2 · NDK 22.0.7026061.
assets(메뉴 그림 · 코어 정보 · 셰이더 …)는 저장소에 없고 공식 빌드 서버가 따로 넣으므로, 같은 판 공식 APK 에서 풀어 쓴다.
"""
import argparse
import os
import secrets
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HB = os.path.join(ROOT, 'haebang')
PHOENIX = os.path.join(ROOT, 'pkg', 'android', 'phoenix')
CACHE = os.path.join(HB, '.cache')
OUT = os.path.join(HB, 'out')
KEYDIR = os.path.join(HB, 'keystore')
KEYSTORE = os.path.join(KEYDIR, 'hbretroarch.jks')
KEYPROPS = os.path.join(KEYDIR, 'keystore.local.properties')

UPSTREAM_VERSION = '1.22.2'
OFFICIAL_APK_URL = 'https://buildbot.libretro.com/stable/%s/android/RetroArch_aarch64.apk' % UPSTREAM_VERSION
LOCAL = os.environ.get('LOCALAPPDATA', '')
SDK = os.environ.get('ANDROID_HOME') or os.path.join(LOCAL, 'Android', 'Sdk')
JDK11 = os.environ.get('HB_JDK11') or os.path.join(os.path.expanduser('~'), '.jdks', 'jdk-11.0.32.1+1')
NDK_VERSION = '22.0.7026061'


def log(msg):
    print('[hb-build] ' + msg, flush=True)


def ensure_assets():
    """공식 APK 의 assets/ 를 phoenix/assets 로. 판 표식이 같으면 건너뛴다."""
    dest = os.path.join(PHOENIX, 'assets')
    stamp = os.path.join(dest, '.hb_assets_version')
    if os.path.isfile(stamp) and open(stamp, encoding='utf-8').read().strip() == UPSTREAM_VERSION:
        log('assets 그대로 씀 (%s)' % UPSTREAM_VERSION)
        return
    os.makedirs(CACHE, exist_ok=True)
    apk = os.path.join(CACHE, 'RetroArch_aarch64-%s.apk' % UPSTREAM_VERSION)
    if not os.path.isfile(apk):
        log('공식 APK 받는 중: ' + OFFICIAL_APK_URL)
        tmp = apk + '.part'
        urllib.request.urlretrieve(OFFICIAL_APK_URL, tmp)
        os.replace(tmp, apk)
    if os.path.isdir(dest):
        shutil.rmtree(dest)
    log('assets 푸는 중')
    n = 0
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            name = info.filename
            if not name.startswith('assets/') or name.endswith('/'):
                continue
            rel = name[len('assets/'):]
            # zip 안 경로가 밖으로 나가지 않게
            target = os.path.normpath(os.path.join(dest, rel))
            if not target.startswith(os.path.normpath(dest) + os.sep):
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with z.open(info) as src, open(target, 'wb') as out:
                shutil.copyfileobj(src, out)
            n += 1
    with open(stamp, 'w', encoding='utf-8') as f:
        f.write(UPSTREAM_VERSION)
    log('assets %d개' % n)


def ensure_local_properties():
    path = os.path.join(PHOENIX, 'local.properties')
    sdk = SDK.replace('\\', '\\\\').replace(':', '\\:')
    with open(path, 'w', encoding='utf-8') as f:
        f.write('sdk.dir=%s\n' % sdk)


def read_props(path):
    out = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                k, v = line.split('=', 1)
                out[k.strip()] = v.strip()
    return out


def ensure_keystore():
    """서명 열쇠 — 한 번 만들면 계속 같은 것을 써야 기기에서 업데이트가 된다(잃으면 지우고 다시 깔아야 함). git 에 안 들어간다."""
    if os.path.isfile(KEYSTORE) and os.path.isfile(KEYPROPS):
        return read_props(KEYPROPS)
    os.makedirs(KEYDIR, exist_ok=True)
    pw = secrets.token_urlsafe(24)
    keytool = os.path.join(JDK11, 'bin', 'keytool.exe' if os.name == 'nt' else 'keytool')
    log('서명 열쇠 새로 만듦: ' + KEYSTORE)
    subprocess.run([keytool, '-genkeypair', '-keystore', KEYSTORE, '-storetype', 'PKCS12',
                    '-storepass', pw, '-keypass', pw, '-alias', 'hbretroarch',
                    '-keyalg', 'RSA', '-keysize', '4096', '-validity', '18250',
                    '-dname', 'CN=HB RetroArch, O=HAE-BANG'], check=True)
    props = {'RELEASE_STORE_FILE': KEYSTORE.replace('\\', '/'), 'RELEASE_STORE_PASSWORD': pw,
             'RELEASE_KEY_ALIAS': 'hbretroarch', 'RELEASE_KEY_PASSWORD': pw}
    with open(KEYPROPS, 'w', encoding='utf-8') as f:
        f.write('# HB RetroArch 서명 열쇠 — 백업해 둘 것. git 에 올리지 말 것.\n')
        for k, v in props.items():
            f.write('%s=%s\n' % (k, v))
    return props


def gradle(props, task):
    env = dict(os.environ)
    env['JAVA_HOME'] = JDK11
    env['ANDROID_HOME'] = SDK
    env['ANDROID_SDK_ROOT'] = SDK
    env['PATH'] = os.path.join(JDK11, 'bin') + os.pathsep + env.get('PATH', '')
    wrapper = os.path.join(PHOENIX, 'gradlew.bat' if os.name == 'nt' else 'gradlew')
    cmd = [wrapper, '--no-daemon', task]
    for k, v in props.items():
        cmd.append('-P%s=%s' % (k, v))
    log('gradle ' + task)
    # 열쇠 비밀번호가 화면에 찍히지 않게 명령 줄은 그대로 보여 주지 않는다.
    r = subprocess.run(cmd, cwd=PHOENIX, env=env)
    if r.returncode != 0:
        sys.exit('gradle 실패 (%d)' % r.returncode)


def collect():
    src_dir = os.path.join(PHOENIX, 'build', 'outputs', 'apk', 'haebang', 'release')
    apks = [f for f in os.listdir(src_dir) if f.endswith('.apk')]
    if not apks:
        sys.exit('APK 가 안 나왔습니다: ' + src_dir)
    os.makedirs(OUT, exist_ok=True)
    dest = os.path.join(OUT, 'HBRetroArch-%s-hb1.apk' % UPSTREAM_VERSION)
    shutil.copy2(os.path.join(src_dir, apks[0]), dest)
    log('완료: %s (%.1f MB)' % (dest, os.path.getsize(dest) / 1048576))
    return dest


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--skip-assets', action='store_true')
    args = ap.parse_args()
    if not os.path.isdir(JDK11):
        sys.exit('JDK 11 이 없습니다: %s (HB_JDK11 로 지정)' % JDK11)
    if not os.path.isdir(os.path.join(SDK, 'ndk', NDK_VERSION)):
        sys.exit('NDK %s 이 없습니다: %s' % (NDK_VERSION, os.path.join(SDK, 'ndk')))
    if not args.skip_assets:
        ensure_assets()
    ensure_local_properties()
    props = ensure_keystore()
    gradle(props, 'assembleHaebangRelease')
    collect()


if __name__ == '__main__':
    main()
