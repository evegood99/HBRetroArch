# HB RetroArch — 코어 브릿지를 넣은 RetroArch 안드로이드 빌드

공식 RetroArch(libretro/RetroArch) 안드로이드 빌드에 **바깥에서 코어를 보고 받는 코어 브릿지**를 더한 비공식 빌드.
해방툴(PC, adb)과 HB런처(기기 안 앱)가 게임을 넣기 전에 코어가 있는지 확인하고, 없으면 바로 받게 하려는 것이다.

- 저장소: <https://github.com/evegood99/HBRetroArch> (가지 `haebang`, libretro/RetroArch 포크)
- 내려받기: <https://github.com/evegood99/HBRetroArch/releases> — APK 와 서명 인증서 SHA-256 은 릴리스 설명에 있다
- libretro 팀과 관계없는 비공식 빌드다. 이 빌드의 문제는 이 저장소의 Issues 로.

| | 공식 RetroArch | HB RetroArch |
|---|---|---|
| 패키지 | `com.retroarch` · `.aarch64` · `.ra32` | `com.haebang.retroarch` (나란히 깔림) |
| 코어 목록을 바깥에서 | 못 봄(`/data/data/<pkg>/cores` 는 앱 전용, adb 도 거절) | `content query` 로 봄 |
| 코어 받기를 바깥에서 | 없음(`CoreSideloadActivity` 는 파일 복사 후 RetroArch 를 켬) | `content call … install` |
| 패드 버튼 동시 입력 | 둘째 버튼이 다음 프레임에 들어감(격투 · 아케이드 동시 입력 실패) | 같은 프레임에 들어감(입력 스레드) |
| 120Hz 같은 높은 주사율 화면 | 시스템 기본 모드로 돌아 틈틈이 끊김(1칸 · 3칸 엇갈림) | 화면 모드를 `video_refresh_rate`(기본 60)에 맞춤 |
| 바탕 | v1.22.2 | v1.22.2 + `pkg/android/phoenix/haebang/` + `input/drivers/android_input.c` + 첫 화면 알림 창 기다리기 |

고친 업스트림 파일은 넷이다.
- `pkg/android/phoenix/build.gradle` — 맛 `haebang`
- `input/drivers/android_input.c` — 동시 입력
- `pkg/android/phoenix/src/com/retroarch/browser/mainmenu/MainMenuActivity.java` — 알림 허용 창 기다리기 (몇 줄)
- `pkg/android/phoenix/src/com/retroarch/browser/retroactivity/RetroActivityFuture.java` — 화면 주사율 맞추기 (몇 줄)

나머지는 모두 새 파일이다:

```
pkg/android/phoenix/src/com/retroarch/browser/mainmenu/
  NotificationPromptGate.java         알림 허용 창이 닫힐 때까지 첫 화면을 붙잡음
pkg/android/phoenix/src/com/retroarch/browser/retroactivity/
  DisplayRefreshMatch.java            화면 모드를 video_refresh_rate 에 맞춤
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
# → haebang\out\HBRetroArch-1.22.2-<hb 번호>.apk  (arm64-v8a + armeabi-v7a 통합판, 번호는 build.gradle 의 versionName 꼬리)
```

- 도구는 공식 CI 와 같다: **JDK 11**(`%USERPROFILE%\.jdks\jdk-11…`, `HB_JDK11` 로 바꿈) · Gradle 6.7.1(wrapper) · AGP 4.2 · **NDK 22.0.7026061**(`%LOCALAPPDATA%\Android\Sdk\ndk\`).
  Gradle 6.7 은 JDK 17 에서 안 돈다.
- **assets**(메뉴 그림 · 코어 정보 · 셰이더 · 오버레이 · 패드 설정)는 저장소에 없다 — 공식 빌드 서버가 따로 넣는다.
  `build.py` 가 같은 버전 공식 APK(`buildbot.libretro.com/stable/1.22.2/android/RetroArch_aarch64.apk`)에서 풀어 `pkg/android/phoenix/assets/` 에 둔다.
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
- **파일 이름이 다른 코어(Azahar)** — 앱 안 온라인 업데이터는 buildbot 이름 그대로 `<코어>_libretro.so` 로 둔다(Azahar 는 buildbot 에
  `azahar_libretro.so.zip` 으로 올라와 있다). `cores` 는 그 이름도 세고 `file` · `path` 에 실제 이름을 적는다. 둘 다 있으면 `_libretro_android.so` 쪽.
  게임을 켤 때 `LIBRETRO` 에는 `file` 을 쓰면 된다. 브릿지로 받은 코어는 언제나 `<코어>_libretro_android.so` 다.

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
  '있음'은 `<코어>_libretro_android.so` 와 `<코어>_libretro.so`(앱 안 업데이터가 둔 것) 둘 다 본다. `delete` 도 둘 다 지운다.
- 받는 주소는 buildbot `.index-extended` 에 적힌 파일 이름이다(대부분 `<코어>_libretro_android.so.zip`, Azahar 는 `azahar_libretro.so.zip`).
  목록에 없거나 오래됐으면 한 번 새로 받아 보고, 그래도 없으면 규약 이름으로 해 본다. 받은 코어는 언제나 `<코어>_libretro_android.so` 로 풀어 둔다
  (RetroArch 는 코어 정보를 찾을 때 `_android` 꼬리를 떼므로 `azahar_libretro.info` 를 그대로 쓴다).
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

`LIBRETRO` 의 파일 이름은 `cores` 목록의 `file` 을 쓰는 것이 가장 정확하다(앱 안 업데이터로 받은 Azahar 는 `azahar_libretro.so`).

세이브 · 스테이트 · BIOS(`/storage/emulated/0/RetroArch/{saves,states,system}`)는 공식 RetroArch 와 같은 자리를 기본으로 쓴다. 설정(retroarch.cfg)은 따로다.

## 패드 버튼 동시 입력 (입력 스레드)

안드로이드 RetroArch 에서 두 버튼을 동시에 눌러도 게임이 동시 입력으로 받지 못하는 문제
(libretro/RetroArch #2043 · #13011 · #17823 · #18552 · #19643)를 고쳤다.

**원인**
- 안드로이드 InputDispatcher 는 앱이 앞서 받은 이벤트를 모두 '처리 완료'(finishEvent)해야 다음 키 이벤트를 보낸다
  (`InputDispatcher::shouldWaitToSendKeyLocked` — "we must serialize key events with respect to all prior input events").
- 공식 드라이버는 입력 큐를 프레임마다 한 번(`android_input_poll`)만 읽는다.
- 그래서 A·B 를 같은 순간에 눌러도 이번 프레임에 A 를 읽고 끝내야 B 가 오고, B 는 다음 프레임에 읽힌다. 버튼이 셋이면 두 프레임 차이가 난다.
- 공식판의 Input Block Timeout(0~4ms)은 매 프레임 그만큼 기다려 B 를 잡으려는 것이다. 매 프레임 시간을 쓰고, 4ms 안에 오지 않으면 실패한다.

**고침** — `input/drivers/android_input.c`
- 입력 큐를 전용 스레드의 루퍼에 붙인다.
- 이미 아는 패드의 키 · 축 이벤트는 오는 즉시 처리하고 바로 끝낸다. 디스패처가 다음 키를 1ms 안팎에 보내므로 같은 프레임에 들어온다.
- 키는 live(지금 상태) + latch(지난 읽기 뒤 한 번이라도 눌림)에 적는다.
  `android_input_poll` 이 프레임마다 `live | latch` 를 `android_key_state` 로 내놓는다.
  그래서 한 프레임 안에서 상태가 바뀌지 않고, 프레임보다 짧은 누름도 사라지지 않는다.
- 같이 누른 키도 하나씩 오므로(키마다 약 1~2ms) 프레임 상태를 내놓기 직전에 키가 막 들어왔다면 2.5ms 조용해질 때까지 기다린다.
  최대 4ms 이고, 키가 들어온 프레임에서만 기다린다. 실측으로는 키 프레임에서 평균 0.3~0.5ms 였다.
- 터치 · 마우스 · 키보드 장치, 처음 보는 장치(연결 처리)는 예전처럼 메인 스레드가 처리한다(이벤트는 메인 스레드가 끝낸다).
- Input Block Timeout 은 입력 스레드가 켜져 있으면 쓰지 않는다(필요 없음).

**기기에서 바꿔 보기** (adb, 앱을 다시 켜면 적용)

```sh
adb shell setprop debug.hbra.inputthread 0   # 공식 방식(비교용) — 기본은 입력 스레드
adb shell setprop debug.hbra.inputlog 1      # logcat 'HBInput': 키 이벤트 · 프레임마다 코어가 보는 눌림/뗌
adb shell input gamepad keycombination -t 120 KEYCODE_BUTTON_A KEYCODE_BUTTON_B   # 동시 입력 흉내
```

**확인(RG 476H · 안드로이드 13 · snes9x)** — 주입한 A+B 동시 입력이 같은 프레임에 들어간 횟수:
공식 방식 0/30, 입력 스레드 150/150(처음 보는 장치의 첫 입력 포함). 기다림은 키 프레임에서 평균 0.3~0.5ms, 최대 약 4ms.
짧은 누름 8/8 유지, 홈으로 나갔다 돌아온 뒤(드라이버 다시 시작) 정상, 터치 오버레이 · 메뉴 · 종료 정상,
'unprocessed events' 경고 없음.

## 높은 주사율 화면 (120Hz)

**증상**: 120Hz 화면 기기(RG 476H 등)에서 게임이 틈틈이 잠깐 느려지거나 끊겨 보인다. 공식 RetroArch 도 같다.

**원인**
- 앱이 바라는 주사율을 말하지 않으면 시스템은 화면을 높은 모드(120Hz)로 돌린다.
- RetroArch 는 `video_refresh_rate`(기본 60)를 화면 주사율로 알고, 소리 속도에 맞춰 1초에 약 60프레임을 만든다.
- 120Hz 에서 고르게 보이려면 프레임마다 정확히 2칸(16.7ms)씩 보여야 한다.
  그런데 소리 시계와 화면 시계가 천천히 어긋나서, 프레임이 화면 갱신 시점 근처에 걸리는 구간마다 1칸(8.3ms) · 3칸(25ms)이 엇갈린다.
- 영상 스레드를 꺼도 같다. 2칸마다 내보내기(`video_swap_interval = 2`)는 Vulkan 에서 먹히지 않는다
  (120프레임으로 내달리다 소리 버퍼가 차면 0.15초씩 멈춘다).

**고침**(hb4, `DisplayRefreshMatch`)
- 게임 화면(`RetroActivityFuture`)이 앞에 올 때 실행 값 `REFRESH` 가 없으면, `video_refresh_rate` 에 가장 가까운 화면 모드(같은 해상도)를 요청한다.
- `REFRESH` 를 주면 공식판처럼 그 값을 쓴다. `REFRESH 0` 이면 시스템에 맡긴다(비교용).
- RetroArch 설정의 화면 주사율을 바꾸면 다음에 앞으로 올 때 그 값을 따른다.

**확인(RG 476H · SFC · 약 40초)** — 화면에 프레임이 나온 간격:

| | 2칸(16.7ms) | 1칸 / 3칸 |
|---|---|---|
| 120Hz 그대로 | 1139 | 705 / 683 |
| 60Hz 모드 | 2513 (전부) | 0 / 0 |

```sh
adb shell am start -n com.haebang.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture -e REFRESH 0 …   # 예전 동작(비교용)
adb shell logcat -s HBRefresh   # 고른 모드
```

## 알림 허용 창 (안드로이드 13+)

코어를 받을 때 진행 알림을 띄우려고 알림 채널을 만든다(`CoreInstallService`).
안드로이드 13+ 는 targetSdk 32 이하 앱(RetroArch 는 28)이 알림 채널을 가지고 있으면, 앱을 아이콘으로 켤 때 시스템이
"알림을 보내도록 허용하시겠습니까?" 창을 그 앱의 작업 위에 대신 띄운다.

공식 RetroArch 의 첫 화면(`MainMenuActivity`)은 곧바로 게임 화면(`RetroActivityFuture`, 따로 된 작업)으로 넘어가며 끝난다.
그래서 이 창이 0.2초 만에 덮이고, 답을 못 한 채 아이콘으로 켤 때마다 다시 떴다(hb2 까지).

**고침**(hb3, `NotificationPromptGate`)
- 첫 화면이 넘어가기 직전에 자기 작업의 맨 위를 본다.
- 다른 앱의 화면(그 창)이 있으면 넘어가기를 미룬다.
- 창이 닫혀 첫 화면이 다시 앞에 오면(허용 · 허용 안 함 · 뒤로) 넘어간다.
- 안드로이드 12 이하, 알림이 이미 허용됨, 창이 안 뜸(사용자가 이미 정함)이면 지금처럼 바로 넘어간다.
- 프론트엔드가 게임 화면을 바로 켤 때(ES-DE · 해방런처)는 시스템이 이 창을 띄우지 않는다. 그 길은 바뀌지 않는다.

## 왜 받는 곳을 고정했나

`retroarch.cfg` 의 `core_updater_buildbot_cores_url` 을 따르지 않는다. 그 파일은 `Android/data/…` 에 있어 옛 안드로이드에서는 저장소 권한만 있는 다른 앱도 고칠 수 있다 —
그 값을 따르면 바깥 호출 한 번으로 남이 만든 .so 가 RetroArch 안에서 돌게 된다. `install_file` 을 셸 · 같은 서명으로만 연 것도 같은 까닭이다.

## 새 업스트림 버전으로 올리기

```sh
git remote add upstream https://github.com/libretro/RetroArch.git   # 한 번만
git fetch upstream tag v1.23.0
git rebase --onto v1.23.0 v1.22.2 haebang   # 고친 업스트림 파일은 build.gradle · android_input.c · MainMenuActivity.java · RetroActivityFuture.java(.github/README.md 는 새 파일)
git push --force-with-lease origin haebang
```

`haebang/build.py` 의 `UPSTREAM_VERSION` 을 바꾸면 assets 도 그 버전 공식 APK 에서 다시 푼다.

## 라이선스

RetroArch 는 GPLv3 이다. 이 빌드를 남에게 배포하면 **고친 소스 전체를 함께 공개해야 한다** — 공개 저장소
<https://github.com/evegood99/HBRetroArch> 가 그 자리다. 릴리스마다 그 APK 를 만든 커밋에 태그(`v<버전>-hb<n>`)를 단다.
해방툴 · HB런처는 공급자(IPC)로만 이야기하므로 그 코드까지 GPL 이 되지는 않는다. 코어는 APK 에 넣지 않고 기기가 buildbot 에서 받는다
(코어마다 라이선스가 다르고 상업 이용을 막는 것도 있다).
