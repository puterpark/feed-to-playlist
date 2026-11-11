# feed-to-playlist

구독한 YouTube 채널에 새 영상이 올라오면 내 재생목록에 자동으로 추가합니다.

- **RSS 폴링**: 채널별 `feeds/videos.xml` 을 주기적으로 조회합니다. (API 쿼터를 쓰지 않음)
- **OAuth**: 구독 목록 조회와 재생목록 추가에만 사용합니다. refresh token 은 AES-256-GCM 으로 암호화해 저장합니다.
- **스택**: Spring Boot 3.5 / Java 21 / PostgreSQL / jOOQ

## 동작 방식

1. 웹의 `/oauth/google/login` 에서 Google 계정을 연결하면 계정이 저장되고, 대상 재생목록(`feed.poll.playlist-title`, 기본 "구독 피드", 비공개)을 찾거나 새로 만듭니다.
2. 폴링 1회는 다음 순서로 진행됩니다.
   refresh token 으로 access token 갱신 → 구독 채널 목록 동기화(기본 6시간마다) → 채널별 RSS 병렬 수집 → 새 영상을 게시 시간순으로 재생목록에 추가
3. **신규 구독 채널은 첫 폴링에서 기존 영상을 "본 것"으로만 기록**하고 추가하지 않습니다. (과거 영상이 한꺼번에 쏟아지는 것 방지)
4. 추가에 성공한 영상만 `seen_video` 에 기록하므로, 실패한 영상은 다음 실행에서 재시도됩니다.
5. 중복·비공개·삭제된 영상은 건너뜁니다. API 쿼터 초과, 재생목록 삭제, 재생목록 가득 참(5000개)이면 해당 계정 처리를 중단하고 다음 실행에 이어서 처리합니다.
6. refresh token 이 폐기(`invalid_grant`)되면 계정을 `REVOKED` 로 표시합니다. 다시 로그인하면 복구됩니다.
7. `max-age`(기본 3일)보다 오래된 영상은 새 영상으로 취급하지 않습니다. `skip-shorts` 가 켜져 있으면 쇼츠를, `skip-live` 가 켜져 있으면 라이브·프리미어를 건너뜁니다.

## 실행 구조

앱은 **두 가지 모드**로 쓰고, 둘은 같은 DB 를 공유합니다. 폴링은 Jenkins 같은 외부 스케줄러가 `job` 모드를 주기적으로 호출하는 방식입니다. (resetask-api 와 같은 패턴)

| 모드 | 프로파일             | 역할                                                           |
|----|------------------|--------------------------------------------------------------|
| 웹  | `local` / `prod` | OAuth 연결 화면(`/oauth/google/login`) 제공. 계정을 연결할 때만 떠 있으면 됩니다. |
| 잡  | `job` (웹 서버 없음)  | 폴링 1회 실행 후 종료. 외부 스케줄러가 주기적으로 호출합니다.                         |

- `application.yml`: 기본 프로파일 `local`
- `application-local.yml`: 로컬 개발. `.env` 파일을 읽습니다.
- `application-prod.yml`: 주입된 환경변수만 사용합니다.
- `application-job.yml`: 웹 서버를 끕니다. 서버에서는 `prod,job` 으로 함께 지정합니다.

## 준비

### 1. Google Cloud

1. YouTube Data API v3 를 활성화합니다.
2. **OAuth 동의 화면**의 데이터 액세스에 다음 범위를 추가합니다.
   `openid`, `email`, `https://www.googleapis.com/auth/youtube`
3. 사용자 인증 정보에서 **OAuth 클라이언트 ID(웹 애플리케이션)** 를 만들고, 승인된 리디렉션 URI 에 `GOOGLE_REDIRECT_URI` 값을 **한 글자도 다르지 않게** 등록합니다.
4. **앱을 "프로덕션"으로 게시**합니다. "테스트" 상태에서는 refresh token 이 7일 뒤 만료되고, 테스트 사용자로 등록되지 않은 계정은 로그인할 수 없습니다. (본인 계정만 쓰면 "확인되지 않은 앱" 경고에서 "고급 > 이동"으로 진행하면 됩니다.)

### 2. PostgreSQL

테이블은 앱이 만들지 않으므로 미리 만들어 두세요. (`public` 스키마 기준, 참고용 DDL)

```sql
create table account
(
    id                      uuid                      not null
        primary key,
    google_sub              varchar(64)               not null
        unique,
    email                   varchar(320)              not null,
    refresh_token_enc       varchar(2048)             not null,
    playlist_id             varchar(64),
    status                  varchar(16) default 'ACTIVE'::character varying not null,
    subscriptions_synced_at timestamp,
    created_at              timestamp   default now() not null,
    updated_at              timestamp   default now() not null
);

create table subscription
(
    account_id bigint                  not null,
    channel_id varchar(32)             not null,
    title      varchar(255)            not null,
    baselined  boolean   default false not null,
    created_at timestamp default now() not null,
    primary key (account_id, channel_id)
);

create table seen_video
(
    account_id bigint                  not null,
    video_id   varchar(16)             not null,
    created_at timestamp default now() not null,
    primary key (account_id, video_id)
);

create index seen_video_created_at_ix on seen_video (created_at);
```

- 시간 컬럼은 `timestamp without time zone` 이라 **앱의 시간대에 의존**합니다. 서버에서는 `TZ=Asia/Seoul` 을 지정하세요. (`Dockerfile` 에 설정되어 있음)
- 앱 계정에는 `SELECT, INSERT, UPDATE, DELETE` 와 스키마 `USAGE` 권한이 필요합니다. 코드 생성에는 `SELECT` 만 필요합니다.

## 설정

### 환경변수

| 변수                                         | 설명                                                                  |
|--------------------------------------------|---------------------------------------------------------------------|
| `JDBC_URL`, `JDBC_USER`, `JDBC_PASSWORD`   | PostgreSQL 접속 정보. 예) `jdbc:postgresql://host:5432/feed_to_playlist` |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | OAuth 클라이언트                                                         |
| `GOOGLE_REDIRECT_URI`                      | 승인된 리디렉션 URI 와 일치해야 함. 서버는 `https://도메인/oauth/google/callback`      |
| `TOKEN_ENCRYPTION_KEY`                     | refresh token 암호화 키. base64 32바이트: `openssl rand -base64 32`        |
| `ALLOWED_EMAILS`                           | (선택) 쉼표 구분. 지정하면 해당 계정만 연결 허용, 비우면 누구나 연결 가능                        |
| `POLL_MAX_AGE`                             | (선택, 기본 `3d`) 이 시간보다 오래된 영상은 새 영상으로 취급하지 않음                         |
| `POLL_SUBSCRIPTION_SYNC_INTERVAL`          | (선택, 기본 `6h`) 구독 채널 목록 재동기화 주기                                      |
| `POLL_RSS_CONCURRENCY`                     | (선택, 기본 `8`) RSS 동시 요청 수                                            |

- 시간 값은 `72h`, `3d`, `10m` 처럼 단위 접미사로 씁니다.
- `POLL_*` 는 줄을 **지우면** 기본값이 쓰입니다. `POLL_MAX_AGE=` 처럼 값만 비워 두면 빈 문자열이 들어가므로 주의하세요.
- 쇼츠 제외(`feed.poll.skip-shorts`)와 재생목록 제목(`feed.poll.playlist-title`)은 `application-*.yml` 에서 직접 바꿉니다.
- `TOKEN_ENCRYPTION_KEY` 를 바꾸면 저장된 refresh token 을 복호화할 수 없으므로 계정을 다시 연결해야 합니다.

### `.env`

로컬에서는 프로젝트 루트의 `.env` 를 사용합니다. (`local` 프로파일일 때만 읽음)

```bash
cp .env.example .env   # 값을 채웁니다
```

- `.env` 는 git 에 올리지 않고(`.gitignore`), `.env.example` 만 커밋합니다.
- 변수를 추가하거나 이름을 바꾸면 `.env.example` 도 함께 고치세요.
- 서버에서는 `docker-compose.yml` 과 같은 디렉터리에 `.env` 를 두고 `chmod 600 .env` 로 소유자만 읽게 합니다.
- docker-compose 는 `.env` 값의 `$` 를 변수 치환으로 해석합니다. `$` 가 들어간 값(비밀번호 등)은 단일 따옴표로 감싸거나 `$$` 로 쓰세요.

## 로컬 개발

jOOQ 코드는 **DB 에 접속해서 생성**됩니다. `compileJava` 가 `jooqCodegen` 에 의존하므로, 빌드할 때 `JDBC_*` 를 **셸 환경변수로** 줘야 합니다. (Gradle 은 `.env` 를 읽지 않음)

```bash
JDBC_URL=jdbc:postgresql://host:5432/feed_to_playlist JDBC_USER=... JDBC_PASSWORD=... ./gradlew build
```

- 생성 결과는 `src/generated/jooq/` 에 만들어지며 git 에서 제외됩니다.
- 테이블 구조를 바꾸면 위와 같은 환경변수로 `./gradlew jooqCodegen` 을 다시 실행하세요.

```bash
# 웹 모드 (.env 를 읽음): 브라우저에서 http://127.0.0.1:8080/oauth/google/login 으로 계정 연결
./gradlew bootRun
```

```bash
# 폴링 1회 실행
java -jar build/libs/feed-to-playlist.jar pollFeedJob --spring.profiles.active=local,job
```

**잡 이름(`pollFeedJob`)은 반드시 첫 번째 인자**여야 합니다. `--spring.profiles.active=...` 같은 옵션을 먼저 쓰면 잡 이름으로 잘못 읽혀 아무것도 실행되지 않고 정상 종료합니다. 프로파일을 환경변수(`SPRING_PROFILES_ACTIVE`)
로 주면 이 문제를 피할 수 있습니다.

## 알아둘 점

- YouTube API 일일 쿼터 기본 10,000 → `playlistItems.insert`(50)로 **하루 약 200개 추가**가 한도입니다. 구독 목록 조회는 페이지당 1이라 미미합니다.
- RSS 는 채널당 최신 15개만 제공합니다. 폴링 간격 동안 15개를 넘게 올라오는 채널은 일부 누락될 수 있습니다.
- RSS 에는 쇼츠도 포함됩니다. `skip-shorts` 는 쇼츠 여부를 영상마다 따로 확인(HTTP HEAD)하며, 판별에 실패하면 쇼츠가 아닌 것으로 보고 추가합니다.
- 로컬에서 `prod` 프로파일로 띄우면 **운영 DB 와 재생목록을 그대로 건드립니다.** 접속 정보를 확인하고 실행하세요.
