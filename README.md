# Park Lounge

주차하거나 충전을 기다리는 시간을 네 가지 3D 공간과 소리로 채우는 오프라인 차량 휴식 앱입니다.

## 포함 내용

- 앱 버전: `0.3.1` / versionCode `4`
- Android 패키지: `ai.nc.varcospaces`
- 실제 생성한 공간 4개와 음원 12개, 한영 UI, 공간별 소리 조절, 5·10·15분 타이머, 2·3분할 화면
- 공간별 잔잔한 움직임과 움직임 끄기. 감상 중에만 움직이며, 일시정지·차량 제한 시 중단
- P단 및 차량 화면 사용 허용 상태 확인. 비P·상태 미확인 시 재생 중단. 주차 복귀만으로 자동 재생하지 않음
- 감상에는 가입·차량 인터넷 연결이 필요하지 않음. 차량 내 실시간 AI 생성·충전 상태 연동은 제공하지 않음
- 공간 모델은 VARCO 3D, 환경음은 VARCO Sound로 사전에 제작. 서비스명·아이콘은 Park Lounge로 표시하며 사용 기술은 텍스트로 안내

제출용 서명 APK는 `apk/ParkLounge_ParkLounge_release.apk`입니다. SHA-256은 `SHA256SUMS.txt`와 `EXPORT_MANIFEST.json`에서 확인합니다.

## 개발 환경

- macOS 또는 Linux, Bash, Python 3.10 이상, Node.js 18 이상, JDK 17 이상
- Android SDK Platform 34 (`android.jar` 및 `optional/android.car.jar`)
- Android SDK Build Tools 36.0.0, Platform Tools (`adb`)
- 설치된 Pleos Connect 에뮬레이터. `scripts/emu.sh` 기본 AVD 이름은 `Pleos_Connect_v2.1.5_S`이며 설치된 이름으로 바꿀 수 있습니다.

SDK 설치 경로를 지정합니다. macOS의 기본 SDK 위치라면 아래 예시를 사용할 수 있습니다. 다른 환경에서는 실제 SDK 경로로 설정합니다.

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
```

`java`와 `javac`가 JDK 17 이상을 가리키도록 `JAVA_HOME` 또는 PATH를 설정합니다. Build Tools 버전이 다르면 `VARCO_BUILD_TOOLS`를 해당 SDK의 build-tools 디렉터리로 지정합니다.

## 검사와 빌드

저장소를 내려받은 프로젝트 루트에서 실행합니다. 직접 빌드 스크립트는 설치된 SDK와 포함된 ZXing JAR를 사용하며 네트워크 다운로드를 하지 않습니다.

```sh
bash scripts/test.sh
bash scripts/build-apk.sh debug
```

출력: `dist/park-lounge-0.3.1-debug.apk`

테스트에는 Java 정책·오디오·설정, Python 에셋 검증, JavaScript UI 상태·3D 로딩 검사가 포함됩니다. `preflight.py`가 표시하는 `PENDING`은 이 검토용 묶음에 정식 마켓 승인 증거를 넣지 않았다는 뜻이며, 디버그 검사와 빌드를 차단하지 않습니다. 테스트를 실행했다는 사실과 실차 검증·마켓 승인은 별개입니다.

Gradle 프로젝트와 wrapper도 포함했습니다. `./gradlew :app:assembleDebug`는 Gradle·AGP가 캐시되지 않았다면 의존성 다운로드가 필요합니다. 배포 서명키·계정·비밀번호는 포함하지 않습니다. 제공된 서명 APK와 새로 빌드한 디버그 APK는 서명이 다르므로 같은 설치본 위에 덮어쓸 수 없을 수 있습니다. 삭제 후 재설치하면 기기에 저장된 앱 설정은 사라집니다.

## 에뮬레이터 실행

```sh
export VARCO_AVD=설치된_Pleos_AVD_이름
bash scripts/emu.sh start
adb wait-for-device
adb shell getprop sys.boot_completed
```

마지막 명령의 출력이 `1`이고 에뮬레이터 부팅이 완료된 뒤, 한 가지 APK를 선택해 설치합니다.

```sh
# 소스에서 빌드한 디버그 APK
bash scripts/emu.sh install dist/park-lounge-0.3.1-debug.apk

# 제출용 서명 APK가 포함된 묶음일 때
# bash scripts/emu.sh install apk/ParkLounge_ParkLounge_release.apk
```

여러 기기가 연결되어 있으면 `ANDROID_SERIAL`을 지정합니다. 에뮬레이터를 P단 주차 상태로 놓고 공간과 시간을 고른 뒤 ‘휴식 시작’을 누릅니다. 공간 변경 시 재생이 멈추므로 새 공간에서 다시 시작합니다. 차량 미디어 음량도 확인합니다. 두 분할 크기에서 공간 회전·소리 조절·소개·QR을 확인할 수 있습니다.

## 소스와 크레딧

`app/src/main/java/`는 Android 서비스·주차 상태·오디오·QR, `app/src/main/assets/web/`는 UI·3D 표시, `catalog.json`과 `content/`는 포함 콘텐츠와 출처입니다. 공개 생성 에셋의 저자 ID·라이선스·수정 내역은 카탈로그와 앱 안에 보존했습니다. 원본 다운로드·개인 작업 기록·서명키·회사 내부 문서는 이 묶음에 포함하지 않습니다.

- ZXing: `app/src/main/assets/licenses/APACHE-2.0.txt`
- Three.js: `app/src/main/assets/licenses/THREE-LICENSE.txt`
- 종합 고지: `app/src/main/assets/licenses/NOTICE.txt`

소프트웨어, 생성 콘텐츠와 브랜드 표시는 각각의 기존 권리·라이선스를 따릅니다. 이 검토용 소스 제공은 전체 묶음을 새 라이선스로 재배포한다는 의미가 아닙니다.

공모전 제출과 Pleos App Market 정식 출시 심사는 별개입니다. `docs/release-evidence.json`은 검토용 범위만 표시하며, 정식 마켓 승인이나 실차 검증 완료를 주장하지 않습니다.

## 공모전 심사용 저장소

이 저장소는 Park Lounge 0.3.1의 소스와 실행 가능한 서명 APK를 담은 비공개 제출용 사본입니다. 개발에 사용한 계정 자격 증명, 서명키, 참가신청서와 서명 문서는 포함하지 않습니다.

APK 파일명은 팀명과 앱명을 공백 없이 표기한 `ParkLounge_ParkLounge_release.apk`입니다. 소스에서 새로 빌드한 디버그 APK의 서명은 제출 APK와 다릅니다.
