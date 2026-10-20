# HB RetroArch — 코어 브릿지를 넣은 RetroArch 안드로이드 빌드

공식 RetroArch(libretro/RetroArch) 안드로이드 판에 **바깥에서 코어를 보고 받는 코어 브릿지**를 더한 비공식 빌드.
해방툴(PC, adb)과 HB런처(기기 안 앱)가 게임을 넣기 전에 코어가 있는지 확인하고, 없으면 바로 받게 하려는 것이다.

- 저장소: <https://github.com/evegood99/HBRetroArch> (가지 `haebang`, libretro/RetroArch 포크)
- 내려받기: <https://github.com/evegood99/HBRetroArch/releases> — APK 와 서명 인증서 SHA-256 은 릴리스 설명에 있다
- libretro 팀과 관계없는 비공식 빌드다. 이 빌드의 문제는 이 저장소의 Issues 로.

| | 공식 RetroArch | HB RetroArch |
|---|---|---|
| 패키지 | `com.retroarch` · `.aarch64` · `.ra32` | `com.haebang.retroarch` (나란히 깔림) |
| 코어 목록을 바깥에서 | 못 봄(`/data/data/<pkg>/cores` 는 앱 전용, adb 도 거절) | `content query` 로 봄 |
| 코어 받기를 바깥에서 | 없음(`CoreSideloadActivity` 는 파일 복사 후 RetroArch 를 켬) | `content call … install` |
| 바탕 | v1.22.2 | v1.22.2 + `pkg/android/phoenix/haebang/` |

업스트림 파일은 `pkg/android/phoenix/build.gradle` 의 `haebang` 맛(flavor) 두 군데만 고쳤다. 나머지는 모두 새 폴더다:

```
pkg/android/phoenix/haebang/
  AndroidManifest.xml                 공급자 · 서비스 · 권한 (본 매니페스트 위에 합쳐짐)
  src/com/haebang/retroarch/
    CoreBridgeProvider.java           content://com.haebang.retroarch.cores
    CoreInstallService.java           받기 대기열(전경 서비스 + 알림)
    CoreStore.java                    코어 폴더 · CRC · buildbot 목록 · 받기 · 설치
  res/values{,-ko}/strings.xml
haebang/build.py                      빌드
```

## 빌드

```powershell
python haebang\build.py
# → haebang\out\HBRetroArch-1.22.2-hb1.apk  (arm64-v8a + armeabi-v7a 통합판)
```

- 도구는 공식 CI 와 같다: **JDK 11**(`%USERPROFILE%\.jdks\jdk-11…`, `HB_JDK11` 로 바꿈) · Gradle 6.7.1(wrapper) · AGP 4.2 · **NDK 22.0.7026061**(`%LOCALAPPDATA%\Android\Sdk\ndk\`).
  Gradle 6.7 은 JDK 17 에서 안 돈다.
- **assets**(메뉴 그림 · 코어 정보 · 셰이더 · 오버레이 · 패드 설정)는 저장소에 없다 — 공식 빌드 서버가 따로 넣는다.
  `build.py` 가 같은 판 공식 APK(`buildbot.libretro.com/stable/1.22.2/android/RetroArch_aarch64.apk`)에서 풀어 `pkg/android/phoenix/assets/` 에 둔다.
- 서명 열쇠는 처음 빌드 때 `haebang/keystore/` 에 만든다(git 미추적). **잃으면 기기에서 업데이트가 안 된다**(지우고 다시 깔아야 함) — 백업할 것.
- ABI 는 `arm64-v8a` + `armeabi-v7a` 통합판(x86 계열은 뺌). 앱은 설치 때 정해진 **한 ABI 로만** 돈다 —
  64비트 기기는 arm64, 32비트 전용 기기는 armeabi-v7a(`adb install --abi armeabi-v7a` 로 64비트 기기에 32비트로 깔 수도 있다).
  코어도 그 ABI 것이어야 하며, 코어 브릿지는 실제 ABI(`info.abi`)에 맞는 buildbot 폴더에서 받는다.
- targetSdk 28 은 공식 웹 배포판과 같다(앱 폴더 코어 로드 · 옛 저장소 권한).

## 코어 브릿지 API (api_version 1)

권한(authority): `com.haebang.retroarch.cores`

### 읽기 — `content query`

| URI | 줄 |
|---|---|
| `…/info` | api_version · package · version_name · abi · cores_dir · info_dir · system_dir · config_path · buildbot_url · core_count · index_age_ms |
| `…/cores` | core · file · path · size · mtime · crc32 · abi · abi_ok · display_name · system_name · extensions · core_version · firmware_count |
| `…/jobs` | id · core · source · state(queued/downloading/installing/done/failed/cancelled) · progress · bytes · total · error · note · crc32 |
| `…/available` | core · file · date · crc32 · installed · local_crc32 · up_to_date (buildbot `.index-extended`) |

- `?core=snes9x` 한 코어만, `?format=json` 한 줄 `json` 칸에 JSON 배열로(쉼표가 든 값도 안전하게 읽힘), `available?refresh=1` 목록을 지금 받음.
- `crc32` 는 buildbot `.index-extended` 와 같은 방식(풀어 놓은 .so 의 CRC32) — 같으면 최신이다.
- `abi_ok=0` 은 파일은 있지만 지금 앱 ABI 와 안 맞아 못 쓰는 코어(32↔64 로 다시 깐 경우) — '없음' 으로 보면 된다.
- `display_name` 등은 `<info_dir>/<core>_libretro.info` 에서. 정보 파일이 없는 코어는 빈 값.

```sh
adb shell content query --uri content://com.haebang.retroarch.cores/cores
adb shell "content query --uri 'content://com.haebang.retroarch.cores/cores?format=json'"
```

### 부르기 — `content call`

| method | arg · extras | 누가 |
|---|---|---|
| `ping` · `info` | | 누구나 |
| `install` | arg=`snes9x,fbneo` · `force:b:true`(있어도 다시) | 누구나 (공식 buildbot https 에서만 받음) |
| `update` | arg=코어들 · 비우면 깔린 것 전부 — CRC 가 다른 것만 다시 받음 | 누구나 |
| `job` · `cancel` | arg=일 번호 | 누구나 |
| `refresh_index` | | 누구나 |
| `install_file` | arg=기기 안 경로(.so 또는 .so.zip) · `name:s:…`(이름 바꿈) | 셸 · root · MANAGE_CORES |
| `delete` | arg=코어 | 셸 · root · MANAGE_CORES |

돌려주는 Bundle 에는 늘 `json`(전체 결과)이 있다. adb 쪽은 `json=` 뒤를 JSON 으로 풀면 된다.

```sh
adb shell content call --uri content://com.haebang.retroarch.cores --method install --arg snes9x,genesis_plus_gx
# Result: Bundle[{ok=true, job_ids=1,2, json={"method":"install","jobs":[…],"ok":true}}]
adb shell "content query --uri 'content://com.haebang.retroarch.cores/jobs?format=json'"

# PC 에서 받은 코어 넣기(인터넷 없는 기기 등)
adb push snes9x_libretro_android.so.zip /data/local/tmp/
adb shell content call --uri content://com.haebang.retroarch.cores --method install_file --arg /data/local/tmp/snes9x_libretro_android.so.zip
```

- `install` 은 이미 있으면(ABI 도 맞으면) 받지 않고 `state=done, note=already_installed` 로 바로 끝낸다. 같은 코어가 받는 중이면 그 일을 돌려준다.
- 깔린 것 전부 `update` 는 `--arg` 를 **빼고** 부른다(adb `content` 는 빈 `--arg ''` 를 받지 못함).
- 받기는 `CoreInstallService`(전경 서비스, 알림에 진행률)가 차례로 한다 — 부른 쪽은 `jobs` 로 끝났는지 본다.
- 넣기 전에 ELF 머리와 기계 종류(arm64 앱에 32비트 코어 등)를 확인하고, 임시 파일에 다 쓴 뒤 이름을 바꿔 넣는다.
- 앱이 받는 도중 죽으면 그 일은 다음 실행 때 `failed / interrupted` 로 남는다.

### 기기 안 앱(HB런처 등)에서

```java
Uri base = Uri.parse("content://com.haebang.retroarch.cores");
Cursor c = getContentResolver().query(Uri.withAppendedPath(base, "cores"), null, null, null, null);
Bundle r = getContentResolver().call(base, "install", "snes9x", null);
```

안드로이드 11+ 에서 다른 앱의 공급자를 보려면 부르는 앱 매니페스트에 `<queries><package android:name="com.haebang.retroarch"/></queries>` 가 있어야 한다.

### 게임 켜기

공식과 같고 패키지만 다르다:

```sh
am start -n com.haebang.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture \
  -e ROM <롬> -e LIBRETRO /data/data/com.haebang.retroarch/cores/<코어>_libretro_android.so \
  -e CONFIGFILE /storage/emulated/0/Android/data/com.haebang.retroarch/files/retroarch.cfg
```

세이브 · 스테이트 · BIOS(`/storage/emulated/0/RetroArch/{saves,states,system}`)는 공식 RetroArch 와 같은 자리를 기본으로 쓴다. 설정(retroarch.cfg)은 따로다.

## 왜 받는 곳을 고정했나

`retroarch.cfg` 의 `core_updater_buildbot_cores_url` 을 따르지 않는다. 그 파일은 `Android/data/…` 에 있어 옛 안드로이드에서는 저장소 권한만 있는 다른 앱도 고칠 수 있다 —
그 값을 따르면 바깥 호출 한 번으로 남이 만든 .so 가 RetroArch 안에서 돌게 된다. `install_file` 을 셸 · 같은 서명으로만 연 것도 같은 까닭이다.

## 새 업스트림 판으로 올리기

```sh
git remote add upstream https://github.com/libretro/RetroArch.git   # 한 번만
git fetch upstream tag v1.23.0
git rebase --onto v1.23.0 v1.22.2 haebang   # 고친 업스트림 파일은 build.gradle 하나(.github/README.md 는 새 파일)
git push --force-with-lease origin haebang
```

`haebang/build.py` 의 `UPSTREAM_VERSION` 을 바꾸면 assets 도 그 판 공식 APK 에서 다시 푼다.

## 라이선스

RetroArch 는 GPLv3 이다. 이 빌드를 남에게 배포하면 **고친 소스 전체를 함께 공개해야 한다** — 공개 저장소
<https://github.com/evegood99/HBRetroArch> 가 그 자리다. 릴리스마다 그 APK 를 만든 커밋에 태그(`v<판>-hb<n>`)를 단다.
해방툴 · HB런처는 공급자(IPC)로만 이야기하므로 그 코드까지 GPL 이 되지는 않는다. 코어는 APK 에 넣지 않고 기기가 buildbot 에서 받는다
(코어마다 라이선스가 다르고 상업 이용을 막는 것도 있다).
