# HB RetroArch

**RetroArch 안드로이드 비공식 빌드 — 다른 앱이 코어를 확인하고 받을 수 있는 '코어 브릿지'를 더했습니다.**
[해방툴(HAE-BANG Rom Setting Tools)](https://evegoodretro.com) · 해방런처(HB런처)와 함께 쓰려고 만들었습니다.

> **비공식 빌드입니다.** libretro 팀과 관계가 없고 그 팀이 승인한 빌드도 아닙니다.
> RetroArch 는 libretro 팀의 GPLv3 소프트웨어이며, 이 저장소에 고친 빌드의 전체 소스가 있습니다.
> RetroArch 자체의 문제는 [공식 저장소](https://github.com/libretro/RetroArch)가 아니라 여기 [Issues](https://github.com/evegood99/HBRetroArch/issues)에 남겨 주세요.

## 왜 만들었나

안드로이드 RetroArch 는 코어를 앱 전용 폴더(`/data/data/<패키지>/cores`)에 둡니다.
그래서 다른 앱(프론트엔드 · PC 프로그램)은 **어떤 코어가 깔려 있는지 알 수 없고, 코어를 넣어 줄 방법도 없습니다.**
코어가 없는 게임을 띄우면 RetroArch 는 검은 화면에서 멈춥니다.

HB RetroArch 는 RetroArch 1.22.2 에 공급자(ContentProvider) 하나를 더했습니다. 바깥에서 할 수 있는 일은 이렇습니다.

- 깔린 코어 목록을 봅니다. 이름 · 크기 · CRC32 · ABI · 표시 이름 · 확장자가 함께 나옵니다.
- 코어를 이름만으로 받게 합니다. 기기가 공식 libretro buildbot 에서 직접 받습니다.
- 받는 진행 상황을 봅니다.
- 최신 버전인지 봅니다. buildbot 목록과 CRC 를 견줍니다.

또 **패드 버튼 동시 입력** 문제를 고쳤습니다(아래 설명). 그 밖의 동작(메뉴 · 코어 실행 · 설정)은 공식 RetroArch 1.22.2 와 같습니다.

## 공식 RetroArch 와 다른 점

| | 공식 RetroArch | HB RetroArch |
|---|---|---|
| 패키지 | `com.retroarch` · `.aarch64` · `.ra32` | `com.haebang.retroarch` — 공식판과 **나란히 깔립니다** |
| 바깥에서 코어 목록 보기 | 불가 | `content query` / ContentResolver |
| 바깥에서 코어 받기 | 불가 | `content call … install` |
| 패드 버튼 동시 입력 | 둘째 버튼이 다음 프레임에 들어가 격투 · 아케이드 게임의 동시 입력이 자주 실패 | 같은 프레임에 들어갑니다(입력 스레드) |
| ABI | 빌드마다 다름 | arm64-v8a + armeabi-v7a (한 APK) |
| 세이브 · 스테이트 · BIOS · 재생목록 | `/storage/emulated/0/RetroArch` | **같은 폴더를 함께 씁니다** |
| 설정(retroarch.cfg) · 코어 | 앱마다 | 따로입니다(`Android/data/com.haebang.retroarch/files`) |

## 내려받기 · 설치

1. [Releases](https://github.com/evegood99/HBRetroArch/releases)에서 `HBRetroArch-<버전>.apk` 를 받습니다.
2. 기기에 설치합니다(안드로이드 5.0 이상). 공식 RetroArch 를 지우지 않아도 됩니다.
3. 한 번 실행해 저장소 권한을 허용합니다. 첫 실행 때 메뉴 그림 · 코어 정보 같은 기본 자료를 풉니다.
4. 코어는 APK 에 들어 있지 않습니다. RetroArch 메뉴의 온라인 업데이터로 받거나, 해방툴 · 해방런처가 받게 합니다.

서명 인증서 SHA-256: `1e:bf:73:d0:00:f9:c5:5d:19:fc:5d:eb:4b:4d:c8:a8:86:6b:bd:4d:1c:09:a4:13:0a:1d:ee:5c:a7:78:01:76`
(업데이트는 같은 서명이어야 덮어 깔 수 있습니다.)

## 프론트엔드에서 띄우기

공식판과 같은 방법으로 띄우고, 패키지만 바꿉니다.

```sh
am start -n com.haebang.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture \
  -e ROM <롬 경로> \
  -e LIBRETRO /data/data/com.haebang.retroarch/cores/<코어>_libretro_android.so \
  -e CONFIGFILE /storage/emulated/0/Android/data/com.haebang.retroarch/files/retroarch.cfg
```

- **ES-DE** — `ES-DE/custom_systems/es_find_rules.xml` 의 `RETROARCH` 항목 맨 앞에
  `com.haebang.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture` 를 넣습니다.
  ES-DE 를 다시 켜면 이 앱을 먼저 씁니다. 해방툴로 내보내면 자동으로 써 줍니다.
- **Pegasus · Daijisho 등** — 실행 명령의 패키지를 `com.haebang.retroarch` 로 바꿉니다.

## 코어 브릿지 (요약)

권한(authority)은 `com.haebang.retroarch.cores` 입니다.

```sh
# 깔린 코어
adb shell "content query --uri 'content://com.haebang.retroarch.cores/cores?format=json'"
# 받기(기기가 buildbot 에서 직접) → 진행은 …/jobs
adb shell content call --uri content://com.haebang.retroarch.cores --method install --arg snes9x,fbneo
adb shell "content query --uri 'content://com.haebang.retroarch.cores/jobs?format=json'"
```

| 할 일 | 누가 쓸 수 있나 |
|---|---|
| `info` · `cores` · `jobs` · `available` 읽기, `install` · `update` · `cancel` | 누구나 — 받는 곳은 공식 buildbot(https)으로 고정 |
| `install_file`(기기 안 .so / .so.zip 넣기) · `delete` | adb 셸 · root · 같은 서명 권한 `com.haebang.retroarch.permission.MANAGE_CORES` |

전체 규약 · 안드로이드 앱에서 부르는 법은 [haebang/README.md](https://github.com/evegood99/HBRetroArch/blob/haebang/haebang/README.md)에 있습니다.

## 패드 버튼 동시 입력

안드로이드 RetroArch 에서 두 버튼을 동시에 눌러도(예: KOF 회피 A+B, 잡기) 게임이 동시 입력으로 받지 못하는 오래된 문제가 있습니다
([#2043](https://github.com/libretro/RetroArch/issues/2043) · [#13011](https://github.com/libretro/RetroArch/issues/13011) ·
[#19643](https://github.com/libretro/RetroArch/issues/19643) 등).

- **원인**
  - 안드로이드는 앱이 앞의 입력을 "처리 완료"해야 다음 키를 보냅니다.
  - RetroArch 는 입력을 프레임마다 한 번만 읽습니다.
  - 그래서 동시에 누른 둘째 버튼이 다음 프레임에 들어갑니다.
- **고침**: 패드 입력을 전용 스레드가 오는 즉시 읽고 바로 처리 완료를 돌려줍니다. 동시에 누른 버튼이 같은 프레임에 들어갑니다.
  - 프레임보다 짧게 눌린 버튼도 사라지지 않습니다.
  - 공식판의 대안인 Input Block Timeout 은 매 프레임 기다립니다. 여기서는 키가 막 들어온 프레임에서만 아주 잠깐(평균 0.3~0.5ms, 최대 약 4ms) 기다립니다.
- **시험**: RG 476H 에서 A+B 동시 입력을 흉내 냈을 때 같은 프레임에 들어간 횟수는 이렇습니다.
  - 공식 방식 0/30
  - HB RetroArch 150/150

자세한 내용과 기기에서 공식 방식으로 되돌려 비교하는 법은 [haebang/README.md](https://github.com/evegood99/HBRetroArch/blob/haebang/haebang/README.md)에 있습니다.

## 소스에서 빌드

```powershell
python haebang\build.py        # → haebang\out\HBRetroArch-1.22.2-<hb 번호>.apk
```

- 공식 CI 와 같은 도구를 씁니다: JDK 11 · Gradle 6.7.1 · Android Gradle Plugin 4.2 · NDK 22.0.7026061.
- 메뉴 그림 · 코어 정보 · 셰이더 같은 assets 는 RetroArch 저장소에 없습니다. `build.py` 가 같은 버전 공식 APK 에서 풀어 넣습니다.
- 서명 열쇠는 처음 빌드할 때 `haebang/keystore/` 에 만듭니다. git 에는 올리지 않습니다.

고친 업스트림 파일은 `pkg/android/phoenix/build.gradle`(맛 `haebang`)과 `input/drivers/android_input.c`(동시 입력)입니다.
나머지는 새 폴더 `pkg/android/phoenix/haebang/` 과 `haebang/` 에 있습니다.

## 업스트림 따라가기

브랜치 `haebang` 은 RetroArch 태그(지금은 `v1.22.2`) 위에 고친 것만 얹은 것입니다.
새 버전이 나오면 그 태그 위로 다시 얹고, `haebang/build.py` 의 `UPSTREAM_VERSION` 을 바꿉니다.

## 라이선스 · 고지

- 소스는 RetroArch 와 같이 **GPLv3** 입니다([COPYING](https://github.com/evegood99/HBRetroArch/blob/haebang/COPYING)).
- APK 의 assets 는 같은 버전 공식 RetroArch APK 의 것을 그대로 씁니다(libretro assets · core-info · 셰이더 · 오버레이 · 자동 패드 설정). 라이선스는 각 원 저장소를 따릅니다.
- 코어는 들어 있지 않습니다. 사용자의 기기가 libretro buildbot 에서 받습니다. 코어마다 라이선스가 다르고, 상업적 사용을 막는 코어도 있습니다.
- "RetroArch" 와 "libretro" 는 libretro 팀의 이름입니다. 이 빌드는 그 팀의 공식 빌드가 아닙니다.

---

### English (short)

**HB RetroArch** is an unofficial build of RetroArch 1.22.2 for Android (package `com.haebang.retroarch`, installs alongside the official app). It adds a *core bridge* (`content://com.haebang.retroarch.cores`) through which other apps can list installed cores and ask the app to download cores from the official libretro buildbot. It is not affiliated with or endorsed by libretro. Source is GPLv3; see [haebang/README.md](https://github.com/evegood99/HBRetroArch/blob/haebang/haebang/README.md) for the API and build instructions.
