# autotrading-market-data (standalone)

바이낸스 USDⓈ-M 선물(fapi)의 시세·파생·실시간 스트림을 수집해 **PostgreSQL에 적재하는** 단독 서비스.

> **이 브랜치(`standalone`)는 인프라 의존이 없는 판입니다.**
> Redis도, 공유 네트워크도, 별도로 띄워 둬야 하는 컨테이너도 없습니다.
> `docker compose up` 한 번이면 PostgreSQL까지 같이 뜹니다.
>
> 원본(`main`)은 4-서비스 MSA의 수집 담당이라 Redis Stream/KV로 하류 서비스에 발행하고,
> 공유 외부망 `autotrading-net`에 이미 떠 있는 PostgreSQL·Redis에 붙습니다. 차이는 아래 [본판과의 차이](#본판과의-차이)에.

수집 대상은 기본 `btcusdt` · `ethusdt` · `solusdt` · `xrpusdt`.
**바이낸스 시장데이터는 공개 엔드포인트라 API 키가 필요 없습니다.**

## 시작하기

필요한 것은 **Docker 하나**입니다. (JDK·Gradle은 이미지 안에서 씁니다.)

```bash
git clone -b standalone https://github.com/mbc8222/autotrading-market-data.git
cd autotrading-market-data

cp deploy/market-data.env.example deploy/market-data.env
#  deploy/market-data.env 를 열어 비밀번호 두 곳을 같은 값으로 바꾼다
#    POSTGRES_PASSWORD=...
#    SPRING_DATASOURCE_PASSWORD=...

docker compose up -d --build
docker compose logs -f
```

확인:

```bash
curl http://localhost:8080/actuator/health          # {"status":"UP", ...}
docker compose exec postgres psql -U marketdata -d marketdata \
  -c "select interval, count(*) from marketdata.binance_klines group by 1 order by 1;"
```

첫 기동에는 캔들 7일 + 파생 30일 + 펀딩 전체 히스토리를 REST로 백필하므로 몇 분간 요청이 몰립니다.
그다음부터는 30초(캔들) · 5분(파생) 주기 폴링과 WebSocket 실시간 수신으로 돌아갑니다.

중지·정리:

```bash
docker compose down        # 컨테이너만 내림 (DB 데이터는 볼륨에 남음)
docker compose down -v     # ★ DB 데이터까지 삭제
```

## 무엇이 쌓이나

| 자료 | 소스 | 방식 | 표 |
|---|---|---|---|
| 캔들 1m·5m·15m·1h | `/fapi/v1/klines` | REST 백필 + 30s 폴링 | `binance_klines` |
| 파생 6종 (OI·롱숏비·taker 비율 등) | `/futures/data` | REST 백필(30d) + 5m 폴링 | `futures_*` |
| 펀딩비 | `/fapi/v1/fundingRate` | REST 전체 히스토리 | `futures_funding_rate` |
| 강제 청산 | WS `@forceOrder` | 실시간, 2s flush | `binance_liquidations` |
| 원시 체결 | WS `@aggTrade` + REST 갭 보정 | 버퍼 → 배치 적재, 60s 갭 sweep | `agg_trade` (일별 파티션) |
| 풀북 원시 차분 | WS `@depth@100ms` + REST 스냅샷 | 로컬 북 동기화 | `depth_diff` (일별 파티션, **기본 꺼짐**) |

모든 시각은 UTC(`TIMESTAMPTZ`), 심볼 키는 소문자 페어(`btcusdt`)입니다.

**적재되지 않는 것**: 마크/인덱스 가격, 예상 펀딩비, 호가 상위 20단 요약.
원본에서 이들은 Redis KV로만 나가던 값이라 이 판에는 남을 곳이 없습니다.
필요하면 `MarkPriceWebSocket`·`DepthCollector`에 적재 경로를 직접 붙이면 됩니다.

## ⚠️ 디스크 — 켜기 전에 읽을 것

`agg_trade`·`depth_diff`는 일별 파티션으로 **무한히 쌓입니다.**
`PartitionMaintenance`는 파티션을 **만들기만 하고 지우지 않습니다** — 원본에서 삭제 주체는
별도 서비스(Parquet 이관·검증에 성공한 파티션만 DROP)인데, 이 판에는 그 서비스가 없습니다.

실측(4심볼 기준):

| 플래그 | 기본값 | 일 증가량 |
|---|---|---|
| `collect.depth.enabled` | **`false`** | 일 **11~24GB** |
| `collect.raw.enabled` | `true` | 일 ~1.3GB |

풀북(`depth_diff`)은 그래서 기본으로 꺼 뒀습니다. 켜려면 보존 대책을 먼저 세우세요.
수동 삭제는 파티션 단위로 합니다:

```sql
DROP TABLE marketdata.agg_trade_p20260101;
DROP TABLE marketdata.depth_diff_p20260101;
```

## 설정

플래그는 `src/main/resources/application.properties`에 있고, 컨테이너에서는
`deploy/market-data.env`의 환경변수로 덮어씁니다(Spring relaxed binding — `COLLECT_DEPTH_ENABLED=true` 꼴).

| 키 | 기본값 | 뜻 |
|---|---|---|
| `collect.symbols` | 4심볼 | 소문자 페어, 쉼표 구분 |
| `collect.kline.intervals` | `1m,5m,15m,1h` | |
| `collect.kline.backfill-days` | `7` | 첫 기동 백필 깊이 |
| `collect.futures.enabled` | `true` | 파생 6종 + 펀딩 |
| `collect.ws.enabled` | `true` | WebSocket 전체 |
| `collect.raw.enabled` | `true` | 원시 체결 적재 |
| `collect.depth.enabled` | `false` | 풀북 원시 적재 (디스크 주의) |

시크릿은 `deploy/market-data.env`(운영) / 루트의 `application-local.properties`(개발)에만 둡니다.
둘 다 gitignored이고 저장소에는 `.example`만 있습니다.
`application-local.properties`는 반드시 **프로젝트 루트**에 두세요 — `src/main/resources`에 두면 JAR에 패키징되어 유출됩니다.

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
| 로깅 | Log4j2, prod에서 롤링 파일 |
| 테스트 | JUnit 5 + Testcontainers |

장애 격리에 Resilience4j를 쓰지 않습니다(Boot 4 비호환). 재시도·백오프는 도메인 코드
(`BinanceRestRetry` · `BinanceBanGuard`)가 직접 다룹니다.

## 운영 메모

- **418(IP ban)** 은 `BinanceBanGuard`가 전 REST 수집기를 10분 일괄 중지합니다. 계속 두드리면 ban이 연장됩니다.
- **429** 는 `BinanceRestRetry`가 백오프합니다. 레이트리밋은 IP 단위라 각자의 예산을 씁니다.
- 수집 재개는 **행 기반**(DB `max(ts)`/hwm부터)이라 중단돼도 다음 tick이 같은 지점에서 회수합니다.
- **청산은 복구가 안 됩니다** — REST 엔드포인트가 없어 꺼져 있던 구간은 영구히 빕니다.
  체결은 47시간 안에 켜면 갭 보정기가 메웁니다. 캔들·파생은 무제한 소급됩니다.
- 수집이 끊긴 구간을 **0으로 채우지 마세요.** 없으면 없는 것으로 두는 편이 분석을 오염시키지 않습니다.
- 일부 관할권에서는 `fapi.binance.com`이 차단됩니다(HTTP 451). 그러면 이 서비스는 아무것도 수집하지 못합니다.

## 본판과의 차이

`main` 대비 이 브랜치에서 바뀐 것은 다음이 전부입니다.

| 파일 | 변경 |
|---|---|
| `build.gradle` | `spring-boot-starter-data-redis` 제거 |
| `publish/MarketDataPublisher.java` | 발행 메서드 전부 no-op (클래스·시그니처는 유지) |
| `raw/InfraHealthMetrics.java` | `infra.redis.*` 게이지 제거, PostgreSQL만 |
| `ws/MarkPriceWebSocket.java` | javadoc |
| `application.properties` | `collect.depth.enabled` 기본 `false`, 보존 정책 주석 |
| `docker-compose.yml` | PostgreSQL 포함 자급식, 공유 외부망 제거 |
| `deploy/market-data.env.example` · `application-local.properties.example` | Redis 항목 제거 |
| `README.md` | 이 문서 |

**수집 로직은 한 줄도 다르지 않습니다.** `MarketDataPublisher`를 지우지 않고 빈 구현으로 남긴 것도
호출부 5곳(`KlineCollector` · `AggTradeWebSocket` · `ForceOrderWebSocket` · `MarkPriceWebSocket` ·
`DepthCollector`)을 본판과 동일하게 유지해, 본판의 수집 개선을 가져올 때 충돌 범위를 좁히기 위해서입니다.

## 문서

| 문서 | 내용 |
|---|---|
| [CLAUDE.md](CLAUDE.md) | 패키지 구조, 수집 패턴, 작업 원칙 |
| [docs/adr/001](docs/adr/001-imperative-over-webflux.md) | WebFlux 대신 명령형 + 가상 스레드를 택한 이유 |
| [docs/adr/002](docs/adr/002-spring-jdbc-over-mybatis.md) | DB 접근에 spring-jdbc 표준 도구를 택한 이유 |
