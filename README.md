# autotrading-market-data-standalone

바이낸스 USDⓈ-M 선물(fapi)의 시세·파생·실시간 스트림을 수집해 **PostgreSQL에 적재하는** 단독 서비스.

외부 의존은 **PostgreSQL 접속 하나**뿐입니다. Docker도, 컨테이너도, 메시지 브로커도,
별도 인프라 설정도 필요 없습니다.

## 요구 환경

| | | 비고 |
|---|---|---|
| **JDK 21** | 필수 | Temurin·Zulu·Corretto 등 아무 배포판 |
| **PostgreSQL 13+** | 필수 | 로컬 설치든, 사내 DB든, 클라우드든 상관없음. 개발은 18에서 함 |
| DB 계정 권한 | 필수 | 대상 스키마에 `CREATE TABLE`/`CREATE INDEX` — 아래 [스키마](#스키마) 참고 |
| 바이낸스 API 키 | **불필요** | 시장데이터는 공개 엔드포인트입니다 |
| 네트워크 | | `fapi.binance.com` 아웃바운드. 일부 관할권은 **HTTP 451로 차단**됩니다 |
| 디스크 | | 기본 설정에서 **일 ~1.3GB**. [디스크](#️-디스크--켜기-전에-읽을-것) 절을 꼭 읽으세요 |
| 포트 | | `8080` — actuator(health·metrics)만. `SERVER_PORT`로 변경 |

Gradle은 따로 설치하지 않아도 됩니다 — `gradlew`가 알아서 받습니다.

## 준비

DB와 계정만 미리 만들면 됩니다.

```sql
CREATE DATABASE marketdata;
CREATE USER marketdata WITH PASSWORD '원하는비밀번호';
GRANT ALL ON DATABASE marketdata TO marketdata;
```

## 스키마

스키마와 표 13개는 **앱이 기동하면서 Flyway로 직접 만듭니다.** 미리 준비할 것이 없습니다.
그래서 DB 계정에 `CREATE TABLE`/`CREATE INDEX` 권한이 있어야 합니다.

앱에 DDL 권한을 줄 수 없는 환경이면 `FLYWAY_ENABLED=false`로 끄고
`src/main/resources/db/migration/V*.sql`을 직접 적용하면 됩니다.
그 V파일들이 스키마 정본이고, 컬럼별 설계 주석도 거기 있습니다.

## 설정

전부 환경변수로 덮어쓸 수 있습니다. **파일을 고칠 필요가 없습니다.**
기본값은 `src/main/resources/application.properties`에 있습니다.

### 접속 — 기본값과 다르면 지정

| 환경변수 | 기본값 |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/marketdata?currentSchema=marketdata` |
| `DB_USER` | `marketdata` |
| `DB_PASSWORD` | `marketdata` |
| `DB_SCHEMA` | `marketdata` |
| `FLYWAY_ENABLED` | `true` — 앱에 DDL 권한을 못 줄 때만 `false` |

### 수집 범위

| 환경변수 | 기본값 | 뜻 |
|---|---|---|
| `COLLECT_SYMBOLS` | `btcusdt,ethusdt,solusdt,xrpusdt` | 소문자 페어, 쉼표 구분 |
| `COLLECT_KLINE_BACKFILL_DAYS` | `7` | 첫 기동에 캔들을 며칠치 받아올지 |
| `COLLECT_RAW_ENABLED` | `true` | 원시 체결 적재 |
| `COLLECT_DEPTH_ENABLED` | `false` | 풀북 원시 적재 — **디스크 주의** |

### 기타

| 환경변수 | 기본값 |
|---|---|
| `SERVER_PORT` | `8080` |
| `LOGS_DIRECTORY` | `logs` (`prod` 프로파일에서만 파일로 씀) |

## 무엇이 쌓이나

| 자료 | 소스 | 방식 | 표 |
|---|---|---|---|
| 캔들 1m·5m·15m·1h | `/fapi/v1/klines` | REST 백필 + 30s 폴링 | `binance_klines` |
| 파생 6종 (OI·롱숏비·taker 비율 등) | `/futures/data` | REST 백필(30d) + 5m 폴링 | `futures_*` 6개 |
| 펀딩비 | `/fapi/v1/fundingRate` | REST 전체 히스토리 | `futures_funding_rate` |
| 강제 청산 | WS `@forceOrder` | 실시간, 2s flush | `binance_liquidations` |
| 원시 체결 | WS `@aggTrade` + REST 갭 보정 | 버퍼 → 배치 적재, 60s 갭 sweep | `agg_trade` (일별 파티션) |
| 풀북 원시 차분 | WS `@depth@100ms` + REST 스냅샷 | 로컬 북 동기화 | `depth_diff` (일별 파티션, **기본 꺼짐**) |

표 13개. 시각은 전부 UTC(`TIMESTAMPTZ`), 심볼 키는 소문자 페어(`btcusdt`).

**적재되지 않는 것**: 마크/인덱스 가격, 예상 펀딩비, 호가 상위 20단 요약.
원본에서 이들은 메시지 채널로만 나가던 값이라 이 판에는 남을 곳이 없습니다.
필요하면 `MarkPriceWebSocket`·`DepthCollector`에 적재 경로를 붙이면 됩니다.

## ⚠️ 디스크 — 켜기 전에 읽을 것

`agg_trade`·`depth_diff`는 일별 파티션으로 **무한히 쌓입니다.**
`PartitionMaintenance`는 파티션을 **만들기만 하고 지우지 않습니다.** 보존은 직접 관리해야 합니다.

실측(4심볼 기준):

| 환경변수 | 기본값 | 일 증가량 |
|---|---|---|
| `COLLECT_DEPTH_ENABLED` | **`false`** | 일 **11~24GB** |
| `COLLECT_RAW_ENABLED` | `true` | 일 ~1.3GB |

풀북은 그래서 기본으로 꺼 뒀습니다. 켜려면 보존 대책을 먼저 세우세요.

```sql
-- 지금 있는 파티션 보기
SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'marketdata' AND relname LIKE 'agg_trade_p%' ORDER BY relname;

DROP TABLE marketdata.agg_trade_p20260101;
```

## 주의사항

- **첫 기동에 몇 분간 요청이 몰립니다** — 캔들 7일 + 파생 30일 + 펀딩 전체 히스토리를
  한 번에 받아옵니다. 죽은 게 아닙니다. 그다음부터는 30초(캔들)·5분(파생) 폴링입니다.
- **청산은 꺼져 있던 구간이 영구 손실입니다** — 바이낸스에 과거 청산 조회 API가 없습니다.
  체결은 47시간 안에 다시 켜면 갭 보정기가 메우고, 캔들·파생은 언제든 소급됩니다.
- **수집이 끊긴 구간을 0으로 채우지 마세요.** 없으면 없는 것으로 두는 편이 분석을 오염시키지 않습니다.
- **닫힌 직후 봉은 한 폴링 뒤 다시 써집니다**(종가가 1틱 바뀔 수 있음) →
  소비자는 `close_time < now-90s`만 읽으세요.
- **418(IP ban)** 은 `BinanceBanGuard`가 전 REST 수집기를 10분 일괄 중지합니다.
  계속 두드리면 ban이 연장됩니다. **429** 는 `BinanceRestRetry`가 백오프합니다.
  레이트리밋은 IP 단위입니다.
- 수집 재개는 **행 기반**(DB `max(ts)`/hwm부터)이라 중단돼도 다음 tick이 같은 지점에서 회수합니다.

## 스택

| 영역 | 기술 |
|---|---|
| 런타임 | Java 21 + **Spring Boot 4.1** + Gradle, 가상 스레드 |
| 웹 | **Spring MVC**(`starter-webmvc`) — WebFlux 아님 ([ADR-001](docs/adr/001-imperative-over-webflux.md)) |
| 외부 호출 | `RestClient` (명령형) |
| 실시간 수신 | jakarta.websocket (Tomcat 클라이언트) |
| DB | **spring-jdbc**(`JdbcClient` + `batchUpdate`) + HikariCP — R2DBC·MyBatis 아님 ([ADR-002](docs/adr/002-spring-jdbc-over-mybatis.md)) |
| 스키마 | Flyway — DDL은 Flyway로만, 적용된 V파일은 불변 |
| 관측 | Actuator + Prometheus (`/actuator/prometheus`) |
| 로깅 | Log4j2 |
| 테스트 | JUnit 5 + Testcontainers |

장애 격리에 Resilience4j를 쓰지 않습니다(Boot 4 비호환). 재시도·백오프는 도메인 코드
(`BinanceRestRetry` · `BinanceBanGuard`)가 직접 다룹니다.

**PostgreSQL 전용입니다.** 일별 RANGE 파티션, `ON CONFLICT` 멱등 upsert, BRIN 인덱스에 의존하므로
다른 DBMS로 바꾸려면 적재 계층을 다시 써야 합니다.

## 문서

| 문서 | 내용 |
|---|---|
| [CLAUDE.md](CLAUDE.md) | 패키지 구조, 수집 패턴, 작업 원칙 |
| `src/main/resources/db/migration/V*.sql` | 적재 스키마 정본 (표 13개, 설계 주석 포함) |
| [docs/adr/001](docs/adr/001-imperative-over-webflux.md) | WebFlux 대신 명령형 + 가상 스레드를 택한 이유 |
| [docs/adr/002](docs/adr/002-spring-jdbc-over-mybatis.md) | DB 접근에 spring-jdbc 표준 도구를 택한 이유 |
