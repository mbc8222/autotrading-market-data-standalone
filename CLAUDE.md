# autotrading-market-data (standalone) — 프로젝트 가이드

## 개요
바이낸스 선물(fapi) 데이터를 수집해 **PostgreSQL(raw 히스토리)에 적재하는** 단독 서비스.
외부 의존은 **PostgreSQL 접속 하나**뿐이다. 메시지 브로커·컨테이너·공유 네트워크를 쓰지 않는다.

> 이 브랜치는 `standalone` 이다. 원본(`main`)은 4-서비스 이벤트드리븐 MSA 의 ① 수집 담당이라
> Redis Stream/KV 로 하류(분석·조회 API)에 발행하고, 미리 떠 있는 공유 인프라에 붙는다.
> 이 판은 그 전제를 전부 걷어냈다. 바뀐 파일은 README "본판과의 차이" 참고.

**스택**: Spring Boot 4.1.x, Java 21 + **가상 스레드**(`spring.threads.virtual.enabled=true`), 명령형 Web(MVC),
spring-jdbc(HikariCP) + Flyway, Log4j2, RestClient, jakarta.websocket.
**WebFlux/R2DBC 아님** — docs/adr/001. DB 접근은 MyBatis 아님 — docs/adr/002.

**Boot 4 주의점** (3.x 자료와 다른 부분):
- starter 개명: `starter-web`→`starter-webmvc`, RestClient 설정은 `starter-restclient` 모듈에 분리
- `ClientHttpRequestFactorySettings`→**`HttpClientSettings`** 개명 (BinanceRestClientConfig 참고)
- `DataSourceProperties` 는 `org.springframework.boot.jdbc.autoconfigure` (3.x 의 `...autoconfigure.jdbc` 아님)
- 자동설정 클래스도 모듈별 패키지로 이동했다 — 예: Redis 는 `RedisAutoConfiguration` 이 아니라
  `org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration`.
  `spring.autoconfigure.exclude` 에 옛 이름을 쓰면 **조용히 무시되고 기동은 성공한다**(제외가 안 먹는다).
- resilience 는 외부 라이브러리 대신 **Spring Framework 7 내장**(`@Retryable` 등) 우선 검토 (resilience4j 는 Boot 4 비호환)
- **이 repo 는 public** — 시크릿 절대 커밋 금지

## 빌드 / 실행
```bash
./gradlew bootRun             # 실행 (PostgreSQL 필요). Windows 는 gradlew.bat
./gradlew compileJava         # 컴파일
./gradlew test                # 테스트
```
요구: **JDK 21**(`build.gradle` 의 toolchain 선언). 없으면 Gradle 이 toolchain 을 못 찾아 빌드가 실패한다.

접속 정보는 전부 환경변수로 덮어쓴다(파일 수정 불필요): `DB_URL` · `DB_USER` · `DB_PASSWORD` · `DB_SCHEMA`.
기본값은 `application.properties` 에 있고 로컬 PostgreSQL 을 가정한다.

health: `GET /actuator/health` — db 컴포넌트 UP 확인.

## 스키마
앱이 기동하면서 Flyway 가 `db/migration/V1~V4` 를 적용해 스키마·표를 만든다. 사람이 할 일은 없다.
정본은 그 V파일들 하나뿐이다 — 별도 DDL 사본을 두지 않는다(둘로 나뉘면 반드시 어긋난다. 실제로
BRIN 인덱스가 운영 DB 에만 있고 마이그레이션엔 없던 적이 있어 V4 로 편입했다).

앱에 DDL 권한을 줄 수 없는 환경이면 `FLYWAY_ENABLED=false` 로 끄고 V파일을 직접 적용한다.

## 패키지 구조 (`com.autotrading.autotradingmarketdata`)
```
binance/   BinanceProperties · BinanceRestClientConfig · BinanceFuturesRestApi(fapi 전체)
           BinanceKline · FuturesRows(파생·raw·청산 row 묶음) · BinanceRestException(429/418 구분)
           BinanceBanGuard(418 전 수집기 일괄 10분 중지) · BinanceRestRetry(429 backoff 공통)
collect/   CollectProperties(공유 심볼 목록)
kline/     KlineCollectProperties · KlineCollector · KlineRepository · KlineCollectionScheduler
futures/   FuturesDataCollector(파생 7종, bounded window+hwm) · FuturesRepository
ws/        BinanceWebSocket(베이스: reconnect/circuit/라우팅) · MarkPrice·Depth·AggTrade·ForceOrder
           WebSocketStarter(ApplicationReady 일괄 연결)
depth/     SymbolUnits(정수 단위=10^-precision — exchangeInfo tickSize 는 실제 해상도와 다름: SOL)
           · LocalBook(순수 상태기계, 동기화 규칙) · DepthRows · DepthCollector(심볼별 상태,
           스냅샷은 가상스레드+FAPI 밴가드, 파생물 없음 — 수집기는 원시만)
raw/       BatchBuffer·AggTradeBuffer → RawPersister(전용 워커) · AggTradeRepository(갭 SQL)
           DepthBuffer(2M) → DepthPersister · DepthRepository(depth_diff 배치·sync_events·symbol_units)
           PartitionMaintenance(★생성만 — 삭제 주체 없음) · AggTradeReconciler(60s 갭 보정)
           LiquidationRepository · RawMonitor(15s 구조화 로그) · InfraHealthMetrics(PostgreSQL 게이지)
publish/   MarketDataPublisher — ★standalone 에서는 전 메서드 no-op(발행 대상 없음).
           클래스를 지우지 않고 빈 구현으로 둔 것은 호출부 5곳(Kline·AggTrade·ForceOrder·MarkPrice·
           Depth)을 본판과 동일하게 유지해, 본판의 수집 개선을 가져올 때 충돌을 이 파일 하나로
           한정하기 위해서다.
resources/db/migration/  V1=binance_klines · V2=futures 7종+청산+agg_trade(파티션 부모)
           · V3=depth_diff(파티션 부모)+depth_symbol_units+depth_sync_events · V4=depth_diff BRIN
docs/adr/                아키텍처 결정 기록
```

## 수집 데이터

| 분류 | 소스 | 방식 | 저장 |
|---|---|---|---|
| 캔들 4 TF | /fapi/v1/klines | REST 백필(7d)+30s 폴링 | `binance_klines` |
| 파생 6종 | /futures/data (5m) | REST 백필(30d)+5m 폴링, bounded window 450×5m | `futures_*` 6테이블 |
| 펀딩비 | /fapi/v1/fundingRate | REST 전체 히스토리 | `futures_funding_rate` |
| 강제 청산 | WS @forceOrder (/market) | 실시간, 2s flush+재큐잉 | `binance_liquidations` |
| 원시 체결 | WS @aggTrade (/market) + REST 갭 보정 | 버퍼→배치 적재, 60s 갭 sweep | `agg_trade` (일별 파티션) |
| 풀북 원시 차분 | WS @depth@100ms (/public) + REST depth?limit=1000 스냅샷 | 로컬 북 동기화(공식 규칙: u<lastUpdateId 버림 · 첫 이벤트 U≤lastUpdateId≤u · 이후 pu==직전 u, 갭 시 재스냅샷) | `depth_diff` (일별 파티션, BRIN 만, 기본 꺼짐) + `depth_sync_events`·`depth_symbol_units` |

> **적재 경로가 없는 것**: 마크/인덱스/예상펀딩(@markPrice)·호가 상위 20단 요약·최신가.
> 본판에서 메시지 채널로만 나가던 값이라 이 판에는 남을 곳이 없다.
> 418(IP ban)은 `BinanceBanGuard` 로 모든 REST 수집기 10분 일괄 중지 (계속 두드리면 ban 연장).
> aggTrade 는 유효성 필터(agg_id·price·qty·T 양수) — 쓰레기 행의 watermark 오염 방지.

### ★ 보존 정책 — 지우는 주체가 없다
`PartitionMaintenance` 는 파티션을 **만들기만** 한다(예전의 "90일 무조건 DROP"은 백업 여부를 보지 않아 제거됨).
그래서 보존은 운영자가 직접 관리한다.
- `collect.depth.enabled` 기본 **false** (실측 일 11~24GB)
- `collect.raw.enabled` 기본 true (실측 일 ~1.3GB)
- 수동 삭제: `DROP TABLE marketdata.agg_trade_pYYYYMMDD`

## 관측
- **메트릭** (`/actuator/prometheus`): WS 5종(`ws_connected`·`ws_circuit_open`·`ws_messages`·`ws_reconnects`·`ws_parse_errors`·`ws_last_message_age_seconds`, socket 태그) — BinanceWebSocket 베이스에서 계측.
  raw 5종(`raw_buffer_size/offered/dropped`, `raw_persister_written/lost`) — `RawMonitor` 등록.
  인프라 게이지(`infra_postgres_up`·연결 수/max) — `InfraHealthMetrics`. 속도는 조회 측 `rate()` 로.
- **로그**: `RawMonitor` 가 15s 구조화 한 줄(`[RAW-MON] agg[size hwm(pct) in/s out/s drop] lost`) + 점유 50%·drop/lost 증가 시 WARN/ERROR. 핵심 감시 대상 = "조용한 유실"(버퍼 점유 상승이 선행지표).
- **로그 출력**: Log4j2(logback 제외), 콘솔 기본. `prod` 프로파일에서만 롤링 파일(`logs/market-data.log`, 일별+50MB·gz·14일). 경로는 `LOGS_DIRECTORY` 로 바꾼다.

## 작업 원칙
- **행 기반 resume**: DB max(open_time)/hwm 부터(inclusive) 재개. forming 봉은 `ON CONFLICT` upsert 로 갱신.
- **백필=폴링 동일 로직**: 중단돼도 다음 tick 이 같은 지점부터 회수.
- **폴링 예외 가드**: `@Scheduled` 는 예외 1회로 정지 가능 — 심볼×인터벌 단위 try/catch. 429/418 은 사이클 중단(=백오프).
- **심볼 키는 소문자 페어**("btcusdt") — URL 에서만 대문자.
- **bounded window**: `/futures/data` 는 한 window ≤PAGE 개가 되도록 시간 폭 제한 → 거래소 정렬 가정과 무관하게 무손실.
- **Flyway 마이그레이션은 불변** — 적용된 V파일 수정 금지, 변경은 V(n+1) 로.
- **DDL 은 Flyway 로만** — 코드에서 CREATE TABLE 금지(런타임 파티션 생성은 예외).
- **★바이낸스 선물 WS 라우팅** — `/public`(depth)·`/market`(aggTrade·markPrice·forceOrder)·`/private` 로 분리됨. 라우팅 없는 연결은 **public 만 수신**(market 스트림은 0건, 에러 없이 침묵). `BinanceWebSocket.streamBaseUri()` 로 클라이언트별 라우팅 필수. 풀 diff depth 는 8KB 넘어 `setDefaultMaxTextMessageBufferSize` 상향 필요.
- **닫힌 직후 봉은 다시 써진다** — 한 폴링 뒤 종가가 1틱 바뀔 수 있다. 소비자는 `close_time < now-90s` 만 읽는다.
- **결손은 0 이 아니다** — 수집이 끊긴 구간을 0 으로 채우지 않는다. 청산은 REST 복구가 불가라 꺼져 있던 만큼 영구히 빈다(체결 47h · 파생/캔들 무제한).
- `.idea/` 수정 금지, 프로젝트 루트에 임시 파일 생성 금지.

## Config 키
```properties
binance.rest-base-url=https://fapi.binance.com   # 선물 fapi (현물 api.binance.com 아님!)
binance.connect-timeout=5s / read-timeout=15s    # 폴링 주기보다 짧게
collect.symbols                                  # ${COLLECT_SYMBOLS}
collect.kline.enabled / intervals / backfill-days / page-limit / fixed-delay
collect.futures.enabled / fixed-delay
collect.ws.enabled                               # WebSocket 전체
collect.raw.enabled                              # ${COLLECT_RAW_ENABLED} 원시 체결 적재
collect.depth.enabled                            # ${COLLECT_DEPTH_ENABLED} 풀북 (기본 false — 디스크)
spring.datasource.*                              # ${DB_URL} ${DB_USER} ${DB_PASSWORD}
spring.flyway.enabled / default-schema / schemas # ${FLYWAY_ENABLED} ${DB_SCHEMA}
```
