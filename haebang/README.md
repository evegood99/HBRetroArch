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
| 스레드된 비디오 + 슬립 · 홈에서 돌아오기 | 안드로이드 13 에서 SIGSEGV 로 죽음 | 그대로 이어짐(영상 스레드를 JVM 에 붙임) |
| 살아 있을 때 다른 게임 요청 | 끝나기만 함(한 번 더 눌러야 뜸) | 새 작업으로 다시 띄움(업스트림 #18587) |
| 상태저장 그림 | 안드로이드는 기본 꺼짐 | 기본 켜짐 · 이미 깐 기기도 한 번 켬(해방런처 세이브 창) |
| 상태저장에서 시작 | 명령줄 `-e N` 뿐(안드로이드 인텐트로는 못 넘김) | 인텐트 `ENTRYSLOT`(슬롯 번호 · `auto`), 같은 게임이 떠 있어도 다시 띄움 |
| 세이브 폴더를 바깥에서 | 앱 전용 설정 파일이라 짐작만 | 코어 브릿지 `saves` 조회 |
| 홈 · 슬립으로 나갈 때 롬저장 | `autosave_interval`(기본 10초)마다만 씀 | 바로 씀 · 뒤로 간 동안은 자동 저장이 다시 쓰지 않음(hb10) |
| 나갈 때 자동 상태저장 | 콘텐츠를 닫을 때만(`savestate_auto_save`) | 인텐트 `AUTOSAVESTATE=1` 이면 홈 · 슬립 · 다른 게임 · 끝낼 때 `.state.auto` + 그림 |
| 바깥에서 세이브를 바꾼 뒤 다시 켜기 | 뒤에 떠 있던 게임이 메모리의 옛 세이브로 그 파일을 덮음 | 인텐트 `RELOADSAVES=1` 이면 떠 있던 게임은 쓰지 않고 디스크에서 새로 켬(hb10) |
| 안드로이드 명령(포커스 · 저장 등) | 코어가 프레임을 도는 도중(`retro_run` 안 입력 읽기)에도 처리 | 프레임이 끝난 뒤 처리 |
| 바탕 | v1.22.2 | v1.22.2 + `pkg/android/phoenix/haebang/` + `input/drivers/android_input.c` + 첫 화면 알림 창 기다리기 |

고친 업스트림 파일은 스물이다(대부분 몇 줄).
- `pkg/android/phoenix/build.gradle` — 맛 `haebang`
- `input/drivers/android_input.c` — 동시 입력 · 나갈 때 롬저장 · 자동 상태저장 · 명령을 프레임 밖에서 처리 · 슬립에서 포커스를 늦게 잃으면 롬저장 다시
- `save.c` — 나갈 때 쓴 롬저장을 자동 저장 스레드의 사본으로도 삼기(뒤로 간 동안 다시 쓰지 않게) · 롬저장 파일은 한 번에 한 쪽만 쓰기
- `gfx/video_thread_wrapper.c` — 영상 스레드를 JVM 에 붙이기
- `pkg/android/phoenix/src/com/retroarch/browser/mainmenu/MainMenuActivity.java` — 알림 허용 창 기다리기
- `pkg/android/phoenix/src/com/retroarch/browser/retroactivity/RetroActivityFuture.java` — 화면 주사율 맞추기 · 기본 자료 풀기 · 상태저장 그림 켜기 · 다른 게임 / `ENTRYSLOT` 다시 띄우기 · `RELOADSAVES` 면 쓰지 않고 다시 띄우기 · 나갈 때 저장 동안 CPU 깨워 두기
- `config.def.h` — 상태저장 그림 기본값(안드로이드도 켜짐)
- `frontend/drivers/platform_unix.c` · `retroarch_types.h` · `tasks/task_content.c` · `retroarch.c` · `runloop.h` · `runloop.c` — `ENTRYSLOT` 을 `-e` 로 넘기기 · `-e auto`
- `frontend/drivers/platform_unix.h` · `command.c` · `command.h` · `content.h` · `tasks/task_save.c` · `tasks/task_screenshot.c` · `tasks/tasks_internal.h`
  (+ 위의 `platform_unix.c` · `retroarch.c` · `runloop.c`) — 나갈 때 자동 상태저장(`AUTOSAVESTATE`)

나머지는 모두 새 파일이다:

```
pkg/android/phoenix/src/com/retroarch/browser/mainmenu/
  NotificationPromptGate.java         알림 허용 창이 닫힐 때까지 첫 화면을 붙잡음
pkg/android/phoenix/src/com/retroarch/browser/retroactivity/
  DisplayRefreshMatch.java            화면 모드를 video_refresh_rate 에 맞춤
  BundleAssetsCheck.java              바로 켤 때도 기본 자료(글꼴 · 아이콘 · 코어 정보)를 풀게 함
  SavestateThumbDefault.java          상태저장 그림을 설정 파일마다 한 번 켬
pkg/android/phoenix/haebang/
  AndroidManifest.xml                 공급자 · 서비스 · 권한 (본 매니페스트 위에 합쳐짐)
  src/com/haebang/retroarch/
    CoreBridgeProvider.java           content://com.haebang.retroarch.cores
    CoreInstallService.java           받기 대기열(전경 서비스 + 알림)
    CoreStore.java                    코어 폴더 · CRC · buildbot 목록 · 받기 · 설치
    SaveDirs.java                     그 게임의 롬저장 · 상태저장 폴더(RetroArch 규칙 그대로)
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

## 코어 브릿지 API (api_version 2)

권한(authority): `com.haebang.retroarch.cores`

| api_version | 버전 | 더한 것 |
|---|---|---|
| 1 | hb1~hb7 | info · cores · jobs · available · call |
| 2 | hb8~ | `saves` 조회, `info.caps`, `cores` 의 `corename` · `savestate` |

기능은 `info` 의 `caps`(쉼표로 이은 글자)로 확인한다. 옛 버전에는 칸이 없다.
- `entry_slot` — 인텐트 `ENTRYSLOT`
- `saves` — `saves` 조회
- `thumb_default` — 상태저장 그림 기본 켜짐
- `autosave_leave` — 인텐트 `AUTOSAVESTATE`(나갈 때 자동 상태저장, hb9)
- `reload_saves` — 인텐트 `RELOADSAVES`(바깥에서 세이브를 바꾼 뒤 떠 있던 게임을 쓰지 않고 디스크에서 새로 켜기, hb10)
- `netplay` — 인텐트 `NETPLAY`(`host` | `client`) · `NETPLAYPORT`(1024~65535) · `NETPLAYNICK`(hb11). 해방런처 온라인 오락실의 방: `--host` / `--connect 127.0.0.1` 과 같고, 방장은 127.0.0.1 만 듣고 LAN 찾기 응답 · 공개 로비 알림 · UPnP · 공식 중계를 하지 않는다. 참가자는 방장과 끊기거나 못 붙으면 게임을 끝낸다(런처로 돌아감). 세이브는 RetroArch 가 `<세이브 폴더>/.netplay/` 로 돌린다. 설계 · 실측: 해방툴 저장소 `docs/HBARCADE_NETPLAY_REVIEW_2026-11-10.md` 11절
  - 준 포트는 설정을 다시 읽어도 남는다(11/12 고침, `configuration.c`): 원본은 게임을 올릴 때 코어 · 게임 오버라이드 파일(또는 `--appendconfig`)이 있으면 설정 파일을 다시 읽으면서
    `--port` 값을 retroarch.cfg 의 `netplay_ip_port` 로 덮는다 — 오버라이드를 저장해 둔 코어에서 방장은 55435 를 듣고 참가자는 55435 로 붙어 실패했다(476H 재현 → 고친 뒤 통과).
- `netplay_latency` — 인텐트 `NETPLAYLATENCY`(`"0"`~`"10"`, hb12): 그 방의 입력 지연 프레임. `NETPLAY` = `host` 와 함께 올 때만 받는다 —
  참가자는 악수 때 방장 값을 받는다(공식 RetroArch 참가자도). 런처가 친구와의 ping 으로 골라 준다(기본 0 이면 지연이 전부 되감기로 메워져 친구가 건너뛰어 보인다).
  - 설정(`netplay_input_latency_frames_min`)은 바꾸지 않는다 — 그 실행의 넷플레이 세션에만 걸리고 `retroarch.cfg` 는 그대로다.
    런어헤드 프레임을 빼지 않고 준 값 그대로, 최댓값은 `준 값 + netplay_input_latency_frames_range`(기본 0 → 고정).
  - 다른 값(`abc` · `99` · `03`) · `client` 는 무시하고 설정대로. 다음 실행에 없으면 없는 것.
  - logcat(`RetroArch`): 받을 때 `[ENV] Netplay latency "N".`, 무시할 때 `[ENV] Ignoring netplay latency "…".`, 방을 열 때 `[Netplay] HB input latency frames N.`
- `lib_version` — call `lib_version`(arg = 코어 이름): 코어가 스스로 말하는 이름 · 버전(`retro_get_system_info`) `{"core","crc32","lib_name","lib_version"}`(hb11).
  넷플레이 악수가 견주는 값이 이것이다(예 `v1.0.0.03 261110 GITa836e52`). `cores` 의 `core_version` 은 info 파일의 `display_version` 이라 실제 빌드와 다를 수 있다.
  코어 .so 를 잠깐 실어(`dlopen`) 읽고 닫는다 — 파일 크기 · 시각이 같으면 다시 읽지 않는다. 읽기뿐이라 누구나 부른다.

### 읽기 — `content query`

| URI | 줄 |
|---|---|
| `…/info` | api_version · caps · package · version_name · abi · cores_dir · info_dir · system_dir · config_path · buildbot_url · core_count · index_age_ms |
| `…/cores` | core · file · path · size · mtime · crc32 · abi · abi_ok · display_name · system_name · extensions · core_version · firmware_count · corename · savestate |
| `…/jobs` | id · core · source · state(queued/downloading/installing/done/failed/cancelled) · progress · bytes · total · error · note · crc32 |
| `…/available` | core · file · date · crc32 · installed · local_crc32 · up_to_date (buildbot `.index-extended`) |
| `…/saves?core=<코어>&rom=<롬 전체 경로>` | 그 게임의 롬저장 · 상태저장 폴더 — 아래 '세이브 폴더' |

- `cores` 의 `corename` · `savestate` 는 info 파일 값 그대로다. `savestate` 는 `"true"` · `"false"` · 빈 값(적혀 있지 않음)이다.
  `"false"` 면 그 코어는 상태저장을 못 한다.

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

### 세이브 폴더 — `saves`

retroarch.cfg 는 앱 전용 폴더에 있어 런처가 읽지 못한다. 그래서 코어 브릿지가 대신 읽고 RetroArch 와 같은 규칙으로 편다.

```sh
adb shell "content query --uri 'content://com.haebang.retroarch.cores/saves?format=json&core=snes9x&rom=/storage/…/Super Mario World (USA).zip'"
```

```json
{"ok": true,
 "save_dir": "/storage/emulated/0/RetroArch/saves/Snes9x",
 "state_dir": "/storage/emulated/0/RetroArch/states/Snes9x",
 "base": "Super Mario World (USA)",
 "library_name": "Snes9x", "library_name_from": "info",
 "save_dir_exists": true, "state_dir_exists": true,
 "thumbnail": true, "auto_save": false, "auto_load": false, "state_slot": 0,
 "save_root": "/storage/emulated/0/RetroArch/saves", "state_root": "/storage/emulated/0/RetroArch/states",
 "save_root_config": "…", "state_root_config": "…",
 "sort_savefiles": true, "sort_savestates": true, "sort_savefiles_by_content": false, "sort_savestates_by_content": false,
 "savefiles_in_content_dir": false, "savestates_in_content_dir": false,
 "config_path": "/storage/emulated/0/Android/data/com.haebang.retroarch/files/retroarch.cfg"}
```

- 파일 이름은 `<state_dir>/<base>.state` · `.state1` … · `.state.auto`, 그림은 그 뒤에 `.png`(`Mario.state1.png`), 롬저장은 `<save_dir>/<base>.srm` 이다.
- `rom` 은 URI 값이라 `+` · `&` · `#` 은 `%2B` · `%26` · `%23` 으로 적는다(공백 · 괄호는 그대로 된다).
- `core` · `rom` 이 없으면 폴더 값(`save_root` · `state_root`)과 정렬 설정만 준다.
- `config=<…cfg>` 로 다른 설정 파일을 읽게 할 수 있다(런처가 `CONFIGFILE` 로 다른 파일을 넘길 때). 없으면 기본 retroarch.cfg.
- 실패하면 `{"ok": false, "error": "…"}`.

규칙(RetroArch 1.22.2 `configuration.c` · `runloop.c` 의 `runloop_path_set_basename` · `runloop_path_set_redirect` 를 옮김)
1. 폴더 값은 `savefile_directory` · `savestate_directory` 다. `~` · `:` 로 시작하면 RetroArch 처럼 편다.
   **`default` · 빈 값 · 없는 폴더면 `save_root` 가 빈 값**이다. 이때는 롬이 있는 폴더를 쓴다.
   - 앱 아이콘으로 켜면 기본 폴더(`<내부>/RetroArch/saves`)가 채워진다. 그런데 프런트엔드가 `DATADIR` 없이 게임 화면을 바로 켜면 비어 있다.
   - RG 476H 에서 확인했다. `default` 로 두고 런처처럼 켜면 `<롬 폴더>/Genesis Plus GX/…srm` 에 썼다.
   - 앱 아이콘으로 한 번 켜고 끝내면 설정에 실제 경로가 적히므로, 보통의 기기에는 경로가 적혀 있다.
2. `savefiles_in_content_dir` · `savestates_in_content_dir` 가 켜져 있거나 1 이 빈 값이면 → 롬이 있는 폴더.
3. 내용별 정렬(`sort_*_by_content_enable`)이 켜져 있으면 → 롬의 바로 위 폴더 이름을 붙인다.
   코어별 정렬(`sort_*_enable`, 기본 켜짐)이 켜져 있으면 → 그 뒤에 `library_name` 을 붙인다.
4. `library_name` 은 코어가 실행 때 알려 주는 이름이다. 코어를 올리지 않으므로 info 파일의 `corename` 을 쓴다(`library_name_from: "info"`).
   그 폴더가 없고, 이름을 정규화(소문자 영숫자만)하면 같은 폴더가 있으면 그 폴더를 쓴다(`"folder"`).
5. `base` 는 롬 파일 이름에서 마지막 확장자를 뗀 것이다(`.zip` 이면 zip 이름). 압축 파일 안(`….zip#안/게임.sfc`)이면 안쪽 파일 이름이다.
- 보지 않는 것: 코어 · 게임별 덮어쓰기 설정(`config/<코어>/…cfg`), 넷플레이 클라이언트의 `.netplay` 폴더, 폴더를 못 만들어 되돌아가는 경우.

**확인(RG 476H, hb8)** — 조회 결과와 RetroArch 가 실제로 쓴 자리(로그 `[Override] Redirecting …`)가 12가지 모두 같았다.
- 코어 2개: Genesis Plus GX · Snes9x
- 설정 6가지: 코어별 / 내용별+코어별 / 내용별만 / 롬 폴더에 / 정렬 끔 / `default` 폴더

info 의 `corename` 과 실제 `library_name`(폴더 이름)이 다른 코어도 찾았다. 모두 그 폴더로 맞춰졌다(`library_name_from: "folder"`).

| 코어 | corename | 실제 폴더 |
|---|---|---|
| `mame2010` | `MAME 2010 (0.139)` | `MAME 2010` |
| `mame2015` | `MAME 2015 (0.160)` | `MAME 2015` |
| `mamearcade` | `MAME (Git)` | `MAME` |

`mame2016`(`MAME 2016 (0.174)`)은 아직 폴더가 없어 corename 그대로 준다. 한 번 저장해 폴더가 생기면 그 폴더를 쓴다.

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

**상태저장에서 시작 — `-e ENTRYSLOT <값>`**(hb8, 글자 추가값)

| 값 | 동작 |
|---|---|
| `0` ~ `999` | 명령줄 `-e N`(`--entryslot`)과 같다. `<이름>.state<N>.entry` 가 있으면 그것을, 없으면 `.state<N>`(0번은 `.state`)을 불러온다. 현재 슬롯도 N 이 된다. 그 파일이 없으면 그냥 시작한다. |
| `auto` | `.state.auto` 를 한 번 불러온다. `savestate_auto_load` 설정과 상관없고, 현재 슬롯은 그대로다. |
| 그 밖(`abc` · `1000` · `007` 같은 앞자리 0) | 무시하고 그냥 시작한다(logcat `[ENV] Ignoring entry slot`). |

- 업적 하드코어 · 넷플레이 · 리플레이 재생 중이면 RetroArch 가 원래 거절한다.
- 그 게임이 이미 떠 있어도 `ENTRYSLOT` 이 있으면 새 작업으로 다시 띄워 그 장면에서 시작한다(아래 '다른 게임 요청').
  `ENTRYSLOT` 이 없으면 예전처럼 하던 데서 이어진다.
- 업스트림 동작이라 그대로 둔 것이 있다. 실행 기록(`content_runtime_log`, 기본 켜짐)에 그 게임의 마지막 슬롯(1 이상)이 있으면,
  RetroArch 는 상태저장을 불러온 **뒤** 현재 슬롯을 그 슬롯으로 다시 맞춘다(`runloop_event_init_core` → `runtime_log_init`).
  - `-e N` 도 같다. 불러오는 장면은 N 이 맞다.
  - 다만 그 뒤 게임 안에서 저장하면 마지막 슬롯으로 갈 수 있다.
- 길: 인텐트 → `frontend_unix_get_env`(`platform_unix.c`)가 값을 확인해 `rarch_main_wrap.entry_slot` 에 담는다.
  → `content_load_init_wrap`(`task_content.c`)이 `-e <값>` 으로 바꾼다. → `retroarch.c` 가 숫자는 `entry_state_slot` 에 넣고,
  `auto` 는 HB 표시(`runloop_st->hb_entry_auto`)로 받는다. → `event_init_content`(`runloop.c`)가 불러온다.

```sh
am start -n com.haebang.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture \
  -e ROM <롬> -e LIBRETRO <코어> -e CONFIGFILE <설정> -e ENTRYSLOT 2
```

**나갈 때 자동 상태저장 — `-e AUTOSAVESTATE 1`**(hb9, 글자 추가값) — 아래 '나갈 때 자동 상태저장'. `ENTRYSLOT` 과 함께 줄 수 있다.
값은 `"1"` 만 켬이다. 다른 값 · 없음이면 예전처럼 `savestate_auto_save` 설정만 따른다.

**바깥에서 세이브를 바꾼 뒤 — `-e RELOADSAVES 1`**(hb10, 글자 추가값) — 아래 '다른 게임 요청'. 값은 `"1"` 만 켬이다.
`ENTRYSLOT` · `AUTOSAVESTATE` 와 함께 줄 수 있고, 그 값들은 새로 뜬 게임에 그대로 따른다.

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

## 기본 자료 풀기 (글꼴 · 아이콘 · 코어 정보)

**증상**: 메뉴의 한글이 '??' 로 나오고 아이콘이 빈칸, 코어 목록의 `display_name` 도 빈 값(RG 476H, 2026-11-04).

**원인**
- 기본 자료(`<dataDir>/assets` · `<dataDir>/info` …)는 APK 안에 있고, 설정의 `bundle_assets_extract_version_current` 가 `_last_version` 과 다를 때만 푼다
  (`menu/menu_driver.c` 의 `rarch_menu_init`).
- `current` 와 APK 경로(`bundle_assets_src_path`)를 적는 것은 **아이콘으로 켤 때의 첫 화면**(`UserPreferences.updateConfigFile`)뿐이다.
  해방런처 · ES-DE 처럼 `RetroActivityFuture` 를 바로 켜면 이 단계를 건너뛴다. 공식 RetroArch 도 같다.
- 그 기기에서는 앱이 새로 설치돼(내부 폴더가 빔) 자료가 없었다. 그런데 설정 파일은 옛 설치 것을 adb 로 되돌려 둔 사본이라
  `current == last`(옛 버전 번호)였다. 그래서 RetroArch 는 '이미 풀었다'고 보았다.
- 게다가 그 사본은 주인이 셸(uid 2000) · 644 라 앱이 쓰지 못했다. 아이콘으로 켜도 새 번호를 적지 못하고, 메뉴에서 바꾼 설정도 저장되지 않았다.

**고침**(hb6, `BundleAssetsCheck` — `RetroActivityFuture.onCreate` 맨 앞, RetroArch 가 설정을 읽기 전)
- 설정 파일(`CONFIGFILE`, 없으면 기본 자리)을 앱이 쓸 수 없으면 같은 내용으로 다시 만들어 앱 것으로 바꾼다. 폴더가 앱 것이라 지우고 이름을 바꿀 수 있다.
- 이 APK 버전으로 아직 안 풀었으면 `current` · APK 경로 · 푸는 자리를 적는다(첫 화면이 하던 일). 이미 준비돼 있으면 건드리지 않는다.
- 버전은 같은데 `<dataDir>/assets` 가 없거나 `<dataDir>/info` 가 비었으면 `last` 를 0 으로 되돌려 다시 풀게 한다.
- `bundle_assets_extract_enable = false` 는 따른다. logcat 태그는 `HBAssets`.

## 슬립 · 홈에서 돌아올 때 (스레드된 비디오)

**증상**: '스레드된 비디오'(`video_threaded = true`)를 켜 두면, 게임 중 슬립했다 깨우거나 홈에 나갔다 돌아오는 순간 RetroArch 가 죽는다
(RG 476H · 안드로이드 13, Vulkan · GL 모두, 공식 1.22.2 도 같음, 2026-11-04 HB런처 세션 인계).

```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid … (Thread-2)
#00 libandroid_runtime.so (… nativeSetTransactionHangCallback(…)::$_1 …)
#01 libgui.so (android::BLASTBufferQueue::~BLASTBufferQueue()+372)
#06 libvulkan.so (vulkan::driver::DestroySurfaceKHR(…)+72)
#07 libretroarch-activity.so (vulkan_context_destroy+96)      ← GL 이면 egl_destroy
```

**원인**
- 깨우거나 돌아오면 창 표면이 새로 만들어지고, RetroArch 는 영상 드라이버를 내렸다 다시 올린다.
- 스레드된 비디오에서는 그 일을 영상 스레드(`gfx/video_thread_wrapper.c` 의 `video_thread_loop`)가 한다.
  이 스레드는 JVM 에 붙지 않은 채 VkSurface/EGLSurface 를 지우고, 그때 `ANativeWindow` 의 마지막 참조가 풀린다.
- 안드로이드 13 의 `BLASTBufferQueue` 소멸자는 JNI 전역 참조를 지우느라 `JNIEnv` 를 찾는데, JVM 에 안 붙은 스레드에서는 NULL 이라 죽는다.
- 스레드를 끄면 메인 스레드(시작할 때 JVM 에 붙음)가 같은 일을 해서 괜찮았다.

**고침**(hb7) — `video_thread_loop` 맨 앞에서 안드로이드면 `jni_thread_getenv()` 로 JVM 에 붙인다. 스레드가 끝날 때는
`thread_key` 소멸자(`jni_thread_destruct`)가 뗀다. 표면을 지우는 다른 스레드는 없다(코어 브릿지 · 입력 스레드 · Vulkan 우편함 스레드는 표면을 놓지 않음).

## 다른 게임 요청 (`onNewIntent`)

1.22 의 게임 화면은 살아 있을 때 실행 요청을 다시 받으면, 같은 게임이면 앞으로 가져오고 다른 게임이면 `finish()` + `System.exit(0)` 로 끝나기만 했다.
다시 보내지 않는 프런트엔드(Pegasus 등)는 다른 게임을 한 번 더 눌러야 했다.

**고침**(hb7, 업스트림 #18587 과 같음) — 다른 게임이면 같은 요청을 새 작업(`NEW_TASK | CLEAR_TASK`)으로 다시 띄우고 끝낸다.
해방런처는 끝나 돌아오면 한 번 더 보내는데(1.22.x), 그 길은 쓰이지 않게 될 뿐 그대로 둬도 된다.

**hb8** — 같은 게임이라도 새 요청에 올바른 `ENTRYSLOT` 이 있으면 같은 길로 다시 띄운다. 해방런처는 QUITFOCUS 를 넘기지 않아
RetroArch 가 그 게임으로 뒤에 떠 있는 일이 흔하다. 다시 띄우지 않으면 '이 상태로 시작'이 그냥 이어 하기가 된다.

**hb9** — 다시 띄우기 전에 지금 게임을 나갈 때처럼 저장한다(`hbNativeLeaveSave`, 아래 '나갈 때 자동 상태저장').

**hb10 — `RELOADSAVES`**. 해방런처 세이브 동기화(2단계)는 PC 의 세이브를 받아 기기의 `.srm` · `.stateN` 을 바꾼다.
그런데 RetroArch 가 그 게임을 뒤에 든 채 살아 있으면 받은 파일이 곧 덮인다.
- 같은 게임 요청은 이어 하기가 된다. 메모리의 옛 SRAM 이 다음 자동 저장이나 다음 나가기 때 쓰인다.
- 다른 게임 · `ENTRYSLOT` 요청도 다시 띄우기 전에 hb9 의 나갈 때 저장이 옛 롬저장(과 `.state.auto`)을 쓴다.
- 런처는 RetroArch 가 떠 있는지 알 수 없다(안드로이드 11+ 은 다른 앱 프로세스를 못 본다).

그래서 새 요청에 `RELOADSAVES` = `"1"` 이 있으면 다음처럼 한다.
- 같은 게임이어도 같은 길(새 작업 + `System.exit(0)`)로 다시 띄운다. `ROM` 이 없어도 다시 띄운다.
- 다시 띄우기 전에 나갈 때 저장을 부르지 않는다. 떠 있던 게임은 뒤로 갈 때 롬저장을 이미 썼고(hb8), 그 뒤로 돌지 않았으니 잃을 것이 없다.
  - 앞에 떠 있는 채로 오면(adb 등) 마지막 `autosave_interval` 몫을 잃을 수 있다. 런처는 자기 화면에서만 보낸다.
- 프로세스가 없어 새로 뜨는 경우(`onCreate`)는 할 일이 없다. 디스크에서 읽는 것이 원래 동작이다.
- logcat `HBLeaveSave: RELOADSAVES: relaunching without saving the running game`.

시스템 로그에 `WindowManager: Exception thrown during dispatchAppVisibility … EXITING` 이 남을 수 있다.
옛 프로세스가 끝난 창에 화면 상태를 알리려다 나는 경고로, 다시 띄우는 길(hb7 부터)에서는 늘 나온다.

**확인(hb10, RG 476H · Snes9x)** — 사본 cfg · 시험 폴더. A = Final Fantasy Mystic Quest(SRAM 8KB, 타이틀에서는 안 바꿈),
B = Super Mario World, Y = 요시 아일랜드(SRAM 을 계속 바꿈). '바꿈' 은 그 파일 끝 16바이트를 다른 값으로 바꿔 둔 것(런처가 받은 것처럼).

| 시험 | 결과 |
|---|---|
| Y 를 하다 홈 | `.srm` 을 바로 씀(나갈 때 쓰기 1번) |
| A 홈 → `.srm` 바꿈 → A + `RELOADSAVES` | 새 pid(0.4초). 10초 뒤에도 바꾼 그대로. 다시 홈 → 새 게임의 SRAM 도 바꾼 내용(디스크에서 읽어 시작) |
| A 홈 → `.srm` 바꿈 → B + `RELOADSAVES` | 새 pid. A 의 `.srm` 은 B 를 하다 홈한 뒤에도 바꾼 그대로 |
| A(`AUTOSAVESTATE`) 홈 → `.state.auto` · `.srm` 바꿈 → B + `RELOADSAVES` | 둘 다 바꾼 그대로 |
| `RELOADSAVES` 없이 같은 게임 / 다른 게임 | 같은 pid 로 이어 하기 / 저장 후 다시 띄움(바꾼 `.srm` 이 떠 있던 SRAM 으로 덮임 — hb9 와 같음) |
| A + `RELOADSAVES` + `ENTRYSLOT 2` | 새 pid, `.state2` 를 불러옴, `.srm` 바꾼 그대로 |
| Y 홈 → 30초 | `.srm` 수정 시각 그대로. 이어 하면 10초마다 다시 씀 |
| Y 홈 → 곧바로 `.srm` 바꿈 → 15초 | 5번 모두 그대로(hb9 는 3번 중 2번 덮임) |
| A 가 앞에 떠 있는 채로 `.srm` 바꿈 → A + `RELOADSAVES` | 쓰지 않음, 새 게임은 바꾼 내용 |
| Y 가 앞에 떠 있는 채로 다른 게임 | 0.5초에 다시 뜸, Y 의 롬저장을 씀(자동 저장 잠금 안에서 저장 — 멈추지 않음) |
| Y 슬립 30초 → 깨우기 | `.srm` 그대로, 같은 pid 로 이어짐 |

## 홈 · 슬립으로 나갈 때 롬저장 쓰기

다시 띄우는 길은 `System.exit(0)` 로 끝나 RetroArch 의 마지막 쓰기를 건너뛴다. 롬저장(.srm)은 자동 저장 스레드가
`autosave_interval`(안드로이드 기본 10초)마다만 쓰므로, 그 몫을 잃을 수 있었다.

**고침**(hb8, `android_input.c`)
- 게임 화면이 뒤로 갈 때(홈 · 슬립 · 다른 앱) 안드로이드가 부르는 `onSaveInstanceState` → `APP_CMD_SAVE_STATE` 에서, 게임이 올라와 있으면
  `CMD_EVENT_SAVE_FILES` 로 롬저장을 바로 쓴다. 업스트림 네트워크 명령 `SAVE_FILES` 와 같은 일이다.
  - 메인 스레드에서 쓴다.
  - 이 명령은 안드로이드 쪽이 답을 기다리는 명령이라, 다 쓴 뒤에 뒤로 간다.
- 그래서 런처가 세이브 창을 열 때 .srm 은 최신이다. 그 뒤 `ENTRYSLOT` · 다른 게임으로 다시 띄워도 잃을 것이 없다.
- 게임이 앞에 떠 있는 채로 요청이 오면(adb 등) 나간 적이 없으니 최대 `autosave_interval` 몫은 여전히 쓰이지 않을 수 있다.

**hb10 — 뒤로 간 동안 자동 저장 스레드가 다시 쓰던 것**
- 자동 저장 스레드는 `autosave_interval` 마다 지난번 사본과 코어의 SRAM 을 견줘, 다르면 파일에 쓴다.
- 나갈 때 쓰는 롬저장(`CMD_EVENT_SAVE_FILES`)은 이 사본을 고치지 않았다. 그래서 마지막 차례 뒤에 바뀐 SRAM 을
  다음 차례(나간 뒤 최대 10초)에 한 번 더 썼다. 그 사이 런처가 받은 파일은 옛 SRAM 으로 덮였다.
  - hb9 재현: 요시 아일랜드(SuperFX, SRAM 을 작업 메모리로 계속 바꿈)를 하다 홈 → 곧바로 `.srm` 을 바꿔 둠 → 3번 중 2번 3.0초 · 6.5초 뒤 덮였다.
- **고침**(`save.c`) — 롬저장 한 칸을 다 쓰면 그 칸의 스레드 사본도 지금 SRAM 으로 맞춘다(`autosave_hb_written`).
  - 뒤로 간 동안은 게임이 돌지 않으니 스레드는 쓸 것이 없다. 게임을 이어 하면 SRAM 이 바뀔 때 다시 쓴다.
  - 메인 스레드는 프레임 동안 스레드의 잠금을 쥐고, 안드로이드 명령은 그 프레임 끝(`android_hb_core_run_end`)에서 처리한다.
    그래서 잠금을 쥔 동안이면 다시 잡지 않고 바로 맞춘다(다시 잡으면 멈춘다).
- **슬립**(`android_input.c`) — 슬립에서는 onStop · onSaveInstanceState(나갈 때 저장)가 포커스 잃음보다 먼저 올 때가 많다.
  그 사이 몇 프레임이 돌아 SRAM 이 바뀌면, 그 몫을 스레드가 5~8초 뒤에 썼다(hb10 첫 빌드, 슬립 11번 중 6번).
  - 포커스를 잃을 때 이미 멈춤(`APP_CMD_STOP`) 뒤면 롬저장을 바로 한 번 더 쓴다. 로그 `[SRAM] HB: focus lost after the leave save - writing again.`
  - 고친 뒤 슬립 6번: 5번이 이 길을 탔고, 22초 동안 늦게 쓴 것은 없었다.
- **한 번에 한 쪽만 쓰기**(`save.c`, 11/11 재검토) — 스레드는 사본을 뜬 뒤 잠금 밖에서 파일에 쓴다. 그 순간 나갈 때 쓰기가 같은 파일에 쓰면
  스레드의 옛 사본이 나중에 써질 수 있었고, 위 고침 뒤로는 스레드가 다시 쓰지 않아 옛 내용이 남는다(몇 ms 의 틈 — 기기에서 재현하지는 못했다).
  - 롬저장 파일 쓰기에 잠금 하나(`autosave_hb_io_lock`)를 두었다. 스레드는 그 잠금을 잡은 뒤, 사본을 뜬 뒤로 바깥에서 쓴 적이 있으면(`hb_gen`) 쓰지 않는다.
  - 스레드는 이 잠금과 칸 잠금을 함께 쥐지 않는다(메인 스레드는 프레임 끝에서 칸 잠금을 쥔 채 이 잠금을 잡는다).

**`QUITFOCUS` 로 켠 게임**(11/11 재검토) — `QUITFOCUS` 가 있으면 `onStop` 에서 `System.exit(0)` 로 끝난다. 나갈 때 저장은
`onSaveInstanceState`(`onStop` 뒤)에서 하므로 건너뛰었다(hb10 확인: 홈에서 롬저장 · `.state.auto` 둘 다 안 씀).
- **고침**(`RetroActivityFuture.onStop`) — 끝내기 전에 `hbNativeLeaveSave` 를 부른다. 요시 아일랜드 · N64(그림은 onPause 때 떠 둔 것) 통과.
- 해방런처는 `QUITFOCUS` 를 넘기지 않는다. 넘기는 다른 프런트엔드를 위한 것이다.

## 상태저장 그림

해방런처 세이브 창이 상태저장을 그림으로 보인다. RetroArch 는 상태저장마다 화면을 `<상태저장 파일>.png` 로 둘 수 있다
(`savestate_thumbnail_enable`, `tasks/task_save.c`). 이름은 `Mario.state.png` · `Mario.state1.png` · `Mario.state.auto.png` 이고, 크기는 코어 해상도 그대로다.
공식 안드로이드판은 이 값이 기본으로 꺼져 있다(`config.def.h` 는 x86_64 만 켬).

**고침**(hb8)
- `config.def.h` — 안드로이드도 기본 켜짐(새로 깔면 그대로 켜진다).
- `SavestateThumbDefault` — 이미 깐 기기의 retroarch.cfg 에는 `"false"` 가 적혀 있어 기본값만으로는 안 바뀐다.
  게임 화면이 켜질 때(`RetroActivityFuture.onCreate`, `BundleAssetsCheck` 다음, RetroArch 가 설정을 읽기 전) 다음처럼 한다.
  - 그 설정 파일(`CONFIGFILE`, 없으면 기본 자리)에 대해 **한 번만** 그 줄을 `"true"` 로 고친다.
  - 했다는 표시는 SharedPreferences(`haebang` · `hb_thumb_default_v1:<경로>`)에 남는다. 그래서 그 뒤 사용자가 메뉴에서 끄면 그대로 둔다.
  - 그 한 줄만 바꾸고 다른 줄(순서 · 빈 값)은 그대로 둔다.
- 그림을 끈 채 같은 슬롯에 다시 저장하면 `.png` 는 옛것으로 남는다. 그림 파일이 상태저장보다 오래됐으면 옛 그림이다.

**확인(RG 476H · Genesis Plus GX)**
- hb7→hb8 덮어 설치: 기기 cfg 3,365개 가운데 `savestate_thumbnail_enable` false→true 와 자료 풀기 기록 3줄만 바뀌었다. 사본 cfg 에서 끈 뒤 다시 켜도 false 로 남았다.
- 0 · 1 · 2번 + 자동(끝낼 때) 모두 `.png` 가 생겼다.
  - 저장 순간 화면 = 그림 = `ENTRYSLOT` 으로 불러온 직후 장면이었다.
  - 1번 그림은 그 순간 게임이 화면 전환 중이라 검은색이었다.
- 저장 한 번에 화면이 멈추는 시간: 저장할 때 앞뒤 약 1.2초의 프레임 표시 간격(SurfaceFlinger)에서 가장 긴 것을 쟀다.

| | 6번 중 가장 긴 간격 |
|---|---|
| 저장 안 함 | 17.0ms |
| 그림 켬 | 17.0ms |
| 그림 끔 | 17.1ms |

60Hz 한 칸(16.7ms)을 넘는 멈춤은 없었다. 큰 코어(PS2 · 3DS 등)는 상태저장 자체가 더 오래 걸리지만, 그림이 더하는 몫은 화면 한 장을 읽는 일이다.

## 나갈 때 자동 상태저장 (hb9, `AUTOSAVESTATE`)

해방런처의 '나갈 때 자동 상태저장' 옵션용이다(2026-11-09 사용자 요청, HB런처 세션 인계).
RetroArch 의 `savestate_auto_save` 는 콘텐츠를 **닫을 때만** `.state.auto` 를 쓴다. 해방런처는 QUITFOCUS 를 넘기지 않아,
홈으로 돌아가도 RetroArch 가 뒤에 살아 있어 '닫기'가 일어나지 않았다.

**언제** — 인텐트 `AUTOSAVESTATE` 가 `"1"` 이면(현재 인텐트를 그때마다 읽는다), `savestate_auto_save` 와 상관없이 다음 넷 모두에서 쓴다.

| 언제 | 자리 |
|---|---|
| 홈 · 슬립 | `onSaveInstanceState` → `APP_CMD_SAVE_STATE` — hb8 의 롬저장 쓰기 뒤 |
| 다른 게임 · `ENTRYSLOT` 으로 다시 띄울 때 | Java `onNewIntent` 가 `hbNativeLeaveSave()` 를 부르고 끝날 때까지(최대 5초) 기다린다(`APP_CMD_HB_LEAVE_SAVE`) |
| 정상 종료 · 콘텐츠 닫기 | `retroarch.c` 의 자동 상태저장 세 자리 |

- **무엇을**: `<상태저장 폴더>/<이름>.state.auto` 와 그림 `….state.auto.png` 를 쓴다(`savestate_thumbnail_enable` 이 켜져 있으면). 현재 슬롯 · 설정은 건드리지 않는다.
- **건너뛰기**:
  - 마지막 자동 저장 뒤 게임이 1초(게임 시간)도 안 돌았으면 건너뛴다. 홈에서 이미 썼으면 다른 게임 요청 때는 다시 안 쓴다.
  - 단, 그 사이에 상태를 불러왔거나(슬롯 불러오기 · 되감기 · 불러오기 취소) 리셋했으면 건너뛰지 않는다 —
    게임 시간은 안 흘러도 장면이 바뀌었다(`content_hb_state_changes`, `content_deserialize_state` · `core_reset` 이 센다).
    떠 둔 그림(아래)도 같은 까닭으로 버린다.
  - 업적 하드코어 · 넷플레이 · 리플레이 중이면 쓰지 않는다.
  - 상태저장을 못 하는 코어(MAME 2015 등)는 아무 파일도 쓰지 않는다(로그 `failed in 0 ms`).
- **끊겨도 안전하게**:
  - 동기로 쓰고, 다 쓴 뒤에 안드로이드에 답한다.
  - 상태는 `….hbtmp` 에 다 쓴 뒤 이름을 바꿔 넣는다. 그림도 같다.
  - 그림을 못 만들면 옛 그림을 지운다(옛 그림이 남으면 다른 장면을 보이게 된다).
  - 슬립이면 저장할 때(`onSaveInstanceState`, onStop 뒤) 화면은 이미 꺼져 기기가 잠들 수 있다.
    그래서 `onPause` 부터 그 저장이 끝날 때까지(길어야 30초) 부분 wake lock(`HBRetroArch:leave`)으로 CPU 를 깨워 둔다
    (`WAKE_LOCK` 권한, haebang 매니페스트). `onResume` · `onDestroy` 에서도 놓는다.
- **그림**:
  - 소프트웨어 코어(MD · SFC 등)는 메모리에 있는 마지막 프레임에서 바로 만든다(코어 해상도).
  - GPU 로 그리는 코어(N64 · PS1 HW · PS2 · 3DS)는 사정이 다르다.
    - 뒤로 간 뒤에는 화면(표면)이 없어 GPU 읽기가 실패하거나 검은 그림을 '성공'으로 준다.
      Vulkan 은 예전 읽기의 그림을 줄 수도 있고, 몇 초를 기다리기도 했다.
    - 그래서 화면이 아직 있는 `onPause`(포커스 잃음보다 먼저 온다)와 포커스 잃음에서 화면을 메모리로 떠 두고,
      저장할 때 그 뒤로 게임 시간 1초 안이면 그 그림을 쓴다.
    - 앞에 떠 있을 때(다시 띄우기 · 종료)는 그 자리에서 뜬다.
    - 화면 크기 그대로면 PNG 를 쓰는 데만 3초가 넘게 걸려서(N64 1280×960), 폭 480 안팎으로 줄여 쓴다(N64 426×320).
- logcat 이 아니라 RetroArch 로그에 `[State] HB leave save to "…" succeeded in N ms (thumbnail: screen | kept before leaving | none)` 가 남는다.

**확인(RG 476H, 사본 cfg · `savestate_auto_save = false`)** — 나가는 데 걸린 시간(상태 + 그림, 로그):

| 코어(게임) | 상태 크기 | 홈 | 슬립 | 그림 |
|---|---|---|---|---|
| Genesis Plus GX(Alien Soldier) | 22 KB | 212ms | 208ms | 320×224, 나가기 직전 화면과 같음 |
| SwanStation(Ace Combat 3) | 1.5 MB | 447ms | 466ms | 떠 둔 그림(9~19ms), 축소 |
| Mupen64Plus-Next GLES3(Banjo-Kazooie) | 1.3 MB | 931ms | 2,334ms | 떠 둔 그림, 426×320 |

- 슬립이 긴 것은 화면이 꺼지며 CPU 가 느려지는 몫으로 보인다.
- 정상 종료(MD) 94ms. 옵션 없음 → 안 바뀜. 1초 안에 다시 나감 → 건너뜀.
- 다른 게임 요청은 둘로 나눠 봤다.
  - 홈에 둔 게임: 이미 저장했으므로 그대로 두고 새 게임이 뜬다.
  - 앞에 띄워 둔 게임(N64 포함): 저장한 뒤 새 게임이 뜬다.
- `ENTRYSLOT auto` 로 그 장면에서 시작했다. 슬립 → 깨우기는 같은 pid 로 이어 했다(Vulkan · GL 스레드된 비디오, hb7 시험).

**넓힌 시험(RG 476H, 같은 날 밤)** — 코어마다 홈 · 슬립 · `ENTRYSLOT auto` · 앞에 띄운 채 다른 게임:

| 코어(게임) | 상태 크기 | 홈 | 슬립 | 앞에 띄운 채 다른 게임 | 그림 |
|---|---|---|---|---|---|
| Snes9x(FF Mystic Quest) | 55 KB | 98ms | 78ms | 58ms | 256×224 |
| FinalBurn Neo(1942) | 2 KB | 144ms | 104ms | 73ms | 256×224 |
| melonDS DS(Dawn of Sorrow) | 1.3 MB | 818ms | 770ms | 278ms | 256×384 |
| Flycast(Crazy Taxi, Vulkan) | 5.5 MB | 1,422ms | 1,425ms | 616ms | 떠 둔 그림 426×320 |
| PPSSPP(Metal Slug XX, Vulkan) | 7.2 MB | 1,959ms | — | 893ms | 떠 둔 그림 426×240 |
| LRPS2(Gradius V) | 4.8 MB | 2,035ms | 1,894ms | 784ms | 떠 둔 그림 426×320 |

- 뒤로 간 뒤(홈 · 슬립)가 앞에 있을 때보다 2~3배 느리다. 안드로이드가 뒤로 간 앱의 CPU 를 줄이는 몫으로 보인다.
- 반복: N64 홈 ↔ 이어 하기 10번(806~951ms, 같은 pid), 슬립 ↔ 깨우기 N64 · MD 8번씩 모두 저장.
- 메뉴를 연 채 홈(MD · Flycast): 그림은 메뉴가 아니라 게임 화면. 알림창을 내렸다 올린 뒤 계속하다 홈(N64): 그림은 새 장면.
- 그림 끔 → 옛 `.png` 지움. `savestate_auto_save` 켬 + 종료 → 업스트림 자동 저장 한 번만. 메뉴만 켠 채(게임 없음) 홈 → 아무것도 안 함.
- 상태저장 폴더가 SD 카드여도 이름 바꾸기 덮어쓰기가 된다(두 번). 되감기 · 미리 실행 · 압축 끔 · 남은 `.hbtmp` 도 그대로 된다.
- 3DS(Azahar)는 가진 롬이 암호화본이라 못 쟀다.
- **PPSSPP(Vulkan)는 홈에서 돌아올 때 죽는다(hb9 와 상관없음).** 5번 중 4번이고, 나갈 때 저장을 꺼도,
  명령을 프레임 밖에서 처리하는 것을 뺀 빌드로도 같았다. Vulkan 화면을 다시 만든 뒤 코어가 망가진 함수 포인터를 부른다.
  코어 옵션 `ppsspp_backend = opengl` 이면 4번 다 이어졌다. 나갈 때 저장은 그 앞에 끝나므로 `ENTRYSLOT auto` 로 이어 할 수 있다.
  사용자 결정(2026-11-10): 그대로 둔다 — HB RetroArch · 해방런처 · 해방툴 모두 PPSSPP 코어 기본 설정을 바꾸지 않는다
  (런처의 PSP 기본 실행기는 PPSSPP 앱). 겪은 사용자는 빠른 메뉴 › 코어 옵션에서 OpenGL 로 바꾼다.

## 안드로이드 명령을 코어 프레임 밖에서 (hb9)

안드로이드 생명주기 명령(포커스 · onPause · `onSaveInstanceState` 등)은 RetroArch 의 입력 읽기(`android_input_poll`)가 처리한다.
그런데 대부분의 코어는 `retro_run` **도중에** 입력을 읽으므로, 이 명령들이 코어가 한 프레임을 도는 한가운데서 처리될 수 있었다.
- 상태저장(`retro_serialize`)과 GPU 읽기는 `retro_run` 밖에서 해야 한다.
- Mupen64Plus-Next 는 자기 스택(libco)에서 입력을 읽어, 거기서 부른 JNI 가 `StackOverflowError` 를 냈다.
  그래서 슬립 때 `AUTOSAVESTATE` 를 못 읽어 저장이 빠졌다.

**고침**(`android_input.c` · `runloop.c`)
- `runloop_iterate` 가 코어를 돌리는 동안(`android_hb_core_run_begin` / `_end`)에는, 입력 읽기가 명령 파이프를 만나면 거기서 멈춘다.
  남은 입력 이벤트는 다음 읽기에서 처리한다.
- 프레임이 끝나면 쌓인 명령을 차례로 처리한다. 원래 루프처럼 끝내기 · 다시 올리기(`reinitRequested`)도 확인한다.
- 안드로이드가 답을 기다리는 명령(onPause 등)은 길어야 한 프레임 늦게 답을 받는다.
- 메뉴 뒤에서 코어가 돌 때(`menu_pause_libretro` 끔, `runloop.c` 의 `display_menu_libretro`)도 같다.
- 명령이 아닌 다른 JNI 는 그대로다. 476H 의 N64 에서 패드 R3(오른쪽 스틱 누름)을 누르면 `StackOverflowError` 경고가 두 줄 났다
  (메뉴 · 홈과 상관없이 그 키를 누른 순간, 저장은 됐다). 코어 프레임 안에서 부르는 무언가로 보이며 어느 JNI 인지는 아직 모른다.

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
git rebase --onto v1.23.0 v1.22.2 haebang   # 고친 업스트림 파일은 맨 위 '고친 업스트림 파일' 열아홉(.github/README.md 는 새 파일)
git push --force-with-lease origin haebang
```

`haebang/build.py` 의 `UPSTREAM_VERSION` 을 바꾸면 assets 도 그 버전 공식 APK 에서 다시 푼다.

## 라이선스

RetroArch 는 GPLv3 이다. 이 빌드를 남에게 배포하면 **고친 소스 전체를 함께 공개해야 한다** — 공개 저장소
<https://github.com/evegood99/HBRetroArch> 가 그 자리다. 릴리스마다 그 APK 를 만든 커밋에 태그(`v<버전>-hb<n>`)를 단다.
해방툴 · HB런처는 공급자(IPC)로만 이야기하므로 그 코드까지 GPL 이 되지는 않는다. 코어는 APK 에 넣지 않고 기기가 buildbot 에서 받는다
(코어마다 라이선스가 다르고 상업 이용을 막는 것도 있다).
