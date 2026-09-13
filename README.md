# autotrading-market-data-standalone

바이낸스 USDⓈ-M 선물(fapi)의 시세·파생·실시간 스트림을 수집해 **PostgreSQL에 적재하는** 단독 서비스.

클론해서 바로 돌릴 수 있습니다. Docker도, 컨테이너도, 메시지 브로커도, 별도 인프라 설정도 필요 없습니다.

## 요구 환경

| | | 비고 |
|---|---|---|
| **JDK 21** | 필수 | Temurin·Zulu·Corretto 등 아무 배포판. `java -version` 으로 확인 |
| **PostgreSQL 13+** | 필수 | 로컬 설치든, 사내 DB든, 클라우드든 상관없음. 개발은 18에서 함 |
| DB 계정 권한 | 필수 | 대상 스키마에 `CREATE TABLE`/`CREATE INDEX` — 앱이 기동 시 표를 만듭니다 |
| 바이낸스 API 키 | **불필요** | 시장데이터는 공개 엔드포인트입니다 |
| 네트워크 | | `fapi.binance.com` 아웃바운드 (일부 관할권은 HTTP 451로 차단됩니다) |
| 디스크 | | 기본 설정에서 **일 ~1.3GB**. 아래 디스크 절을 꼭 읽으세요 |
| 포트 | | `8080` (actuator만). `SERVER_PORT` 로 변경 |

Gradle은 따로 설치하지 않아도 됩니다 — `gradlew` 가 알아서 받습니다.

## 시작하기

**1. PostgreSQL에 DB와 계정을 만듭니다**

```sql
CREATE DATABASE marketdata;
CREATE USER marketdata WITH PASSWORD 'marketdata';
GRANT ALL ON DATABASE marketdata TO marketdata;
```

**2. 실행**

```bash
git clone https://github.com/mbc8222/autotrading-market-data-standalone.git
cd autotrading-market-data-standalone
./gradlew bootRun          # Windows: gradlew.bat bootRun
```

스키마와 표는 **앱이 기동하면서 Flyway로 직접 만듭니다.** 미리 준비할 것이 없습니다.

접속 정보가 기본값(`localhost:5432` / `marketdata` / `marketdata`)과 다르면 환경변수로 넘깁니다.
**파일을 고칠 필요가 없습니다.**

```bash
DB_URL='jdbc:postgresql://db.example.com:5432/marketdata?currentSchema=marketdata' \
DB_USER=myuser DB_PASSWORD=secret \
./gradlew bootRun
```

**3. 확인**

```bash
curl http://localhost:8080/actuator/health
psql -U marketdata -d marketdata \
  -c "select interval, count(*) from marketdata.binance_klines group by 1 order by 1;"
```

첫 기동에는 캔들 7일 + 파생 30일 + 펀딩 전체 히스토리를 REST로 백필하므로 몇 분간 요청이 몰립니다.
그다음부터는 30초(캔들)·5분(파생) 폴링과 WebSocket 실시간 수신으로 돌아갑니다.

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
컬럼 정의와 설계 주석은 마이그레이션 파일에 있습니다 — `src/main/resources/db/migration/V*.sql`.

**적재되지 않는 것**: 마크/인덱스 가격, 예상 펀딩비, 호가 상위 20단 요약.
원래 배포에서 이들은 메시지 채널로만 나가던 값이라 이 판에는 남을 곳이 없습니다.
필요하면 `MarkPriceWebSocket`·`DepthCollector` 에 적재 경로를 붙이면 됩니다.

## ⚠️ 디스크 — 켜기 전에 읽을 것

`agg_trade`·`depth_diff` 는 일별 파티션으로 **무한히 쌓입니다.**
`PartitionMaintenance` 는 파티션을 **만들기만 하고 지우지 않습니다.** 보존은 직접 관리해야 합니다.

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

## 설정

전부 환경변수로 덮어쓸 수 있습니다. 기본값은 `src/main/resources/application.properties` 에 있습니다.

| 환경변수 | 기본값 | 뜻 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/marketdata?currentSchema=marketdata` | |
| `DB_USER` / `DB_PASSWORD` | `marketdata` / `marketdata` | |
| `DB_SCHEMA` | `marketdata` | Flyway 대상 스키마 |
| `FLYWAY_ENABLED` | `true` | 앱에 DDL 권한을 못 줄 때만 `false` (그때는 `db/migration/V*.sql` 을 직접 적용) |
| `COLLECT_SYMBOLS` | 4심볼 | 소문자 페어, 쉼표 구분 |
| `COLLECT_KLINE_BACKFILL_DAYS` | `7` | 첫 기동 백필 깊이 |
| `COLLECT_RAW_ENABLED` | `true` | 원시 체결 적재 |
| `COLLECT_DEPTH_ENABLED` | `false` | 풀북 원시 적재 (디스크 주의) |
| `SERVER_PORT` | `8080` | actuator 포트 |
| `LOGS_DIRECTORY` | `logs` | 파일 로그 경로 (`prod` 프로파일에서만 파일로 씀) |

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

## 운영 메모

- **418(IP ban)** 은 `BinanceBanGuard` 가 전 REST 수집기를 10분 일괄 중지합니다. 계속 두드리면 ban이 연장됩니다.
- **429** 는 `BinanceRestRetry` 가 백오프합니다. 레이트리밋은 IP 단위입니다.
- 수집 재개는 **행 기반**(DB `max(ts)`/hwm부터)이라 중단돼도 다음 tick이 같은 지점에서 회수합니다.
- **청산은 복구가 안 됩니다** — REST 엔드포인트가 없어 꺼져 있던 구간은 영구히 빕니다.
  체결은 47시간 안에 켜면 갭 보정기가 메웁니다. 캔들·파생은 무제한 소급됩니다.
- 수집이 끊긴 구간을 **0으로 채우지 마세요.** 없으면 없는 것으로 두는 편이 분석을 오염시키지 않습니다.
- 닫힌 직후 봉은 한 폴링 뒤 다시 써집니다(종가가 1틱 바뀔 수 있음) → 소비자는 `close_time < now-90s` 만 읽으세요.

## 문서

| 문서 | 내용 |
|---|---|
| [CLAUDE.md](CLAUDE.md) | 패키지 구조, 수집 패턴, 작업 원칙 |
| `src/main/resources/db/migration/V*.sql` | 적재 스키마 (표 13개, 설계 주석 포함) |
| [docs/adr/001](docs/adr/001-imperative-over-webflux.md) | WebFlux 대신 명령형 + 가상 스레드를 택한 이유 |
| [docs/adr/002](docs/adr/002-spring-jdbc-over-mybatis.md) | DB 접근에 spring-jdbc 표준 도구를 택한 이유 |
