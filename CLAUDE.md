# autotrading-market-data (standalone) — 프로젝트 가이드

## 개요
바이낸스 선물(fapi) 데이터를 수집해 **PostgreSQL(raw 히스토리)에 적재하는** 단독 서비스.

> **이 브랜치는 `standalone` 입니다.** 원본(`main`)은 4-서비스 MSA의 ① 수집 담당이라
> Redis Stream/KV로 하류(분석·조회 API)에 발행하고, 공유 외부망 `autotrading-net`에 이미 떠 있는
> PostgreSQL·Redis에 붙습니다. 이 판은 그 의존을 전부 걷어내고 **수집 → DB 적재까지만** 합니다.
> 바뀐 파일 목록은 README의 "본판과의 차이" 절에 있습니다.

**스택**: Spring Boot 4.1.x, Java 21 + **가상 스레드**(`spring.threads.virtual.enabled=true`), 명령형 Web(MVC),
spring-jdbc(HikariCP) + Flyway, Log4j2, RestClient, jakarta.websocket.
**WebFlux/R2DBC 아님** — docs/adr/001. DB 접근은 MyBatis 아님 — docs/adr/002.

**Boot 4 주의점** (3.x 자료와 다른 부분):
- starter 개명: `starter-web`→`starter-webmvc`, RestClient 설정 클래스는 `starter-restclient` 모듈에 분리됨
- `ClientHttpRequestFactorySettings`→**`HttpClientSettings`** 개명 (BinanceRestClientConfig 참고)
- `DataSourceProperties` 는 `org.springframework.boot.jdbc.autoconfigure` (3.x 의 `...autoconfigure.jdbc` 아님)
- resilience 는 외부 라이브러리 대신 **Spring Framework 7 내장**(`@Retryable`·`@ConcurrencyLimit`) 우선 검토 (resilience4j 는 Boot 4 비호환)
- **이 repo 는 public** — 시크릿 절대 커밋 금지 (시크릿은 gitignored 파일에만)

## 빌드 / 실행
```bash
# 운영(prod) — Docker. 이것만으로 PostgreSQL 까지 같이 뜬다.
cp deploy/market-data.env.example deploy/market-data.env   # 비밀번호 두 곳 채우기
docker compose up -d --build
docker compose logs -f        # 파일 로그는 호스트 ./logs 에도 보존
docker compose down           # 내림 (DB 볼륨 유지) / down -v 는 DB 삭제
```
```bash
# 개발(local) — 호스트에서 직접 실행. 기본 프로파일 local
./gradlew.bat compileJava     # 컴파일
./gradlew.bat bootRun         # 실행 (PostgreSQL 필요 — compose 의 postgres ports 주석을 풀어 127.0.0.1:5432 발행)
./gradlew.bat test            # 테스트
```
health: `GET /actuator/health` — db 컴포넌트 UP 확인.

## 배포 (Docker, 자급식)
- **`Dockerfile`**(멀티스테이지): 빌드 스테이지에서 컨테이너 내 `./gradlew bootJar` → IDE "Build Artifacts" 함정(thin jar/MANIFEST 중복) 원천 차단·재현성. 런타임=JRE 21, 비루트(appuser).
- **`docker-compose.yml`**: `postgres`(18, 이름 `md-postgres`) + `market-data`(이름 `md-collector`) 두 서비스. `restart: unless-stopped`, `SPRING_PROFILES_ACTIVE=prod`, `env_file: deploy/market-data.env`, `./logs:/var/log/autotrading` 볼륨, DB 는 named volume `pgdata`.
- **공유 외부망 없음.** compose 기본망이 서비스 이름을 DNS 로 풀어주므로 `postgres:5432` 가 그대로 해석된다.
- 포트는 기본적으로 **호스트에 열지 않는다**. actuator 만 `127.0.0.1:8080`. DB 를 외부 툴로 보고 싶으면 compose 의 `ports` 주석을 풀되 `127.0.0.1` 바인딩을 유지할 것.
- `depends_on: service_healthy` — postgres 의 `pg_isready` 가 통과해야 앱이 뜬다.

## 설정 / 시크릿
원칙: **JAR = 환경 무관 공통 설정만. 접속정보/시크릿은 JAR 밖에서 환경별 주입, git 추적 안 함.**

- `src/main/resources/application.properties` — **공통 설정만**(포트·가상스레드·collect.*·binance 타임아웃). 접속정보 없음. 기본 `spring.profiles.active=local`.
- **개발(local)**: 프로젝트 **루트** `application-local.properties`(gitignored). ※ `src/main/resources` 가 아니라 루트 — resources 면 JAR 에 패키징되어 유출된다. 템플릿 `application-local.properties.example`.
- **운영(prod)**: `SPRING_PROFILES_ACTIVE=prod` 가 local 을 override. compose `env_file: deploy/market-data.env` 의 환경변수(relaxed binding)로 주입. 템플릿 `deploy/market-data.env.example`. 같은 파일이 postgres 컨테이너 초기화(`POSTGRES_*`)에도 쓰이므로 **비밀번호 두 항목이 일치해야 한다.**
- 바이낸스 시장데이터는 **공개 엔드포인트라 API 키 불필요**.

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
           PartitionMaintenance(★생성만 — 삭제 주체 없음, 아래 보존 정책 참고) · AggTradeReconciler(60s 갭 보정)
           LiquidationRepository · RawMonitor(15s 구조화 로그) · InfraHealthMetrics(PostgreSQL 게이지)
publish/   MarketDataPublisher — ★standalone 에서는 전 메서드 no-op(발행 대상 없음).
           클래스를 지우지 않고 빈 구현으로 둔 것은 호출부 5곳을 본판과 동일하게 유지해
           본판의 수집 개선을 가져올 때 충돌을 이 파일 하나로 한정하기 위해서다.
resources/db/migration/  V1=binance_klines · V2=futures 7종+청산+agg_trade(파티션 부모) · V3=depth_diff(파티션 부모)+depth_symbol_units+depth_sync_events
docs/adr/  아키텍처 결정 기록
```

## 수집 데이터

| 분류 | 소스 | 방식 | 저장 |
|---|---|---|---|
| 캔들 4 TF | /fapi/v1/klines | REST 백필(7d)+30s 폴링 | `binance_klines` |
| 파생 6종 | /futures/data (5m) | REST 백필(30d)+5m 폴링, bounded window 450×5m | `futures_*` 6테이블 |
| 펀딩비 | /fapi/v1/fundingRate | REST 전체 히스토리 | `futures_funding_rate` |
| 강제 청산 | WS @forceOrder (/market) | 실시간, 2s flush+재큐잉 | `binance_liquidations` |
| 원시 체결 | WS @aggTrade (/market) + REST 갭 보정 | 버퍼→배치 적재, 60s 갭 sweep | `agg_trade` (일별 파티션) |
| 풀북 원시 차분 | WS @depth@100ms (/public) + REST depth?limit=1000 스냅샷 | 로컬 북 동기화(공식 규칙: u<lastUpdateId 버림 · 첫 이벤트 U≤lastUpdateId≤u · 이후 pu==직전 u, 갭 시 재스냅샷) | `depth_diff` (일별 파티션, **인덱스 없음**, 기본 꺼짐) + `depth_sync_events`·`depth_symbol_units` |

> **적재 경로가 없는 것**: 마크/인덱스/예상펀딩(@markPrice)·호가 상위 20단 요약·최신가.
> 본판에서 Redis KV 로만 나가던 값이라 이 판에는 남을 곳이 없다. 필요하면 적재 경로를 직접 붙인다.
> 418(IP ban)은 `BinanceBanGuard` 로 모든 REST 수집기 10분 일괄 중지 (계속 두드리면 ban 연장).
> aggTrade 는 유효성 필터(agg_id·price·qty·T 양수) — 쓰레기 행의 watermark 오염 방지.

### ★ 보존 정책 — 이 판에는 지우는 주체가 없다
본판은 별도 서비스(`autotrading-cold-export`)가 3일 지난 파티션을 Parquet 로 이관·정수검증한 뒤에만 DROP 한다.
standalone 에는 그 서비스가 없고 `PartitionMaintenance` 는 **생성만** 한다. 그래서:
- `collect.depth.enabled` 기본 **false** (실측 일 11~24GB)
- `collect.raw.enabled` 기본 true (실측 일 ~1.3GB)
- 수동 삭제는 파티션 단위: `DROP TABLE marketdata.agg_trade_pYYYYMMDD`

## 관측
- **메트릭** (`/actuator/prometheus`): WS 5종(`ws_connected`·`ws_circuit_open`·`ws_messages`·`ws_reconnects`·`ws_parse_errors`·`ws_last_message_age_seconds`, socket 태그) — BinanceWebSocket 베이스에서 계측.
  raw 5종(`raw_buffer_size/offered/dropped`, `raw_persister_written/lost`) — `RawMonitor` 등록.
  인프라 게이지(`infra_postgres_up`·연결 수/max) — `InfraHealthMetrics`. 속도는 조회 측 `rate()` 로.
- **로그**: `RawMonitor` 가 15s 구조화 한 줄(`[RAW-MON] agg[size hwm(pct) in/s out/s drop] lost`) + 점유 50%·drop/lost 증가 시 WARN/ERROR. 핵심 감시 대상 = "조용한 유실"(버퍼 점유 상승이 선행지표).
- **로그 출력**: Log4j2(logback 제외). 콘솔은 전 프로파일 공통 → Docker 에선 `docker compose logs` 가 캡처. prod 전용 롤링 파일 `/var/log/autotrading/market-data.log`(일별+50MB·gz·14일), compose 볼륨으로 호스트 `./logs` 에 보존.

## 작업 원칙
- **행 기반 resume**: DB max(open_time)/hwm 부터(inclusive) 재개. forming 봉은 `ON CONFLICT` upsert 로 갱신.
- **백필=폴링 동일 로직**: 중단돼도 다음 tick 이 같은 지점부터 회수.
- **폴링 예외 가드**: `@Scheduled` 는 예외 1회로 정지 가능 — 심볼×인터벌 단위 try/catch. 429/418 은 사이클 중단(=백오프).
- **심볼 키는 소문자 페어**("btcusdt") — URL 에서만 대문자.
- **bounded window**: `/futures/data` 는 한 window ≤PAGE 개가 되도록 시간 폭 제한 → 거래소 정렬 가정과 무관하게 무손실.
- **Flyway 마이그레이션은 불변** — 적용된 V파일 수정 금지, 변경은 V(n+1) 로.
- **DDL 은 Flyway 로만** — 코드/`spring.sql.init` 에서 CREATE TABLE 금지.
- **★바이낸스 선물 WS 라우팅** — `/public`(depth)·`/market`(aggTrade·markPrice·forceOrder)·`/private` 로 분리됨. 라우팅 없는 연결은 **public 만 수신**(market 스트림은 0건, 에러 없이 침묵). `BinanceWebSocket.streamBaseUri()` 로 클라이언트별 라우팅 필수. 풀 diff depth 는 8KB 넘어 `setDefaultMaxTextMessageBufferSize` 상향 필요.
- **결손은 0 이 아니다** — 수집이 끊긴 구간을 0 으로 채우지 않는다. 청산은 REST 복구가 불가라 꺼져 있던 만큼 영구히 빈다(체결 47h · 파생/캔들 무제한).
- `.idea/` 수정 금지, 프로젝트 루트에 임시 파일 생성 금지.

## Config 키
```properties
binance.rest-base-url=https://fapi.binance.com   # 선물 fapi (현물 api.binance.com 아님!)
binance.connect-timeout=5s / read-timeout=15s    # 폴링 주기보다 짧게
collect.symbols                                  # 소문자 페어, 쉼표 구분
collect.kline.enabled / intervals / backfill-days / page-limit / fixed-delay
collect.futures.enabled / fixed-delay
collect.ws.enabled                               # WebSocket 전체
collect.raw.enabled                              # 원시 체결 적재
collect.depth.enabled                            # 풀북 원시 적재 (기본 false — 디스크)
spring.datasource.* (?currentSchema=marketdata)  # 환경별 주입. application.properties 엔 없음
spring.flyway.default-schema / schemas           # url 없음 — 메인 DataSource 공유
```
