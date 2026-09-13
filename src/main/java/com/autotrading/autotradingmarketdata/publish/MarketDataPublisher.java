package com.autotrading.autotradingmarketdata.publish;

import com.autotrading.autotradingmarketdata.binance.BinanceKline;
import com.autotrading.autotradingmarketdata.binance.FuturesRows.LiquidationEvent;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 독립 배포판 — <b>아무것도 발행하지 않는다</b>.
 *
 * <p>원본 저장소는 여기서 Redis Stream/KV 로 시장이벤트와 핫상태를 발행해 하류 서비스(분석·조회 API)에
 * 먹인다. 이 판은 <b>수집해서 PostgreSQL 에 적재하는 것까지만</b>이 목적이라 Redis 자체가 없다.
 *
 * <p><b>클래스를 지우지 않고 빈 구현으로 남겨 둔 이유</b>: 호출부 5곳(KlineCollector · AggTradeWebSocket ·
 * ForceOrderWebSocket · MarkPriceWebSocket · DepthCollector)을 원본과 한 글자도 다르지 않게 유지하기 위해서다.
 * 그래야 원본의 수집 로직 변경을 여기로 가져올 때(git merge upstream/main) 충돌이 이 파일 하나로 한정된다.
 *
 * <p>따라서 <b>발행 경로로만 나가던 자료는 standalone 에 남지 않는다</b>:
 * <ul>
 *   <li>마크가격·인덱스·예상펀딩(@markPrice) — DB 적재 경로가 없어 수집해도 버려진다</li>
 *   <li>호가 상위 20단 요약 — 원시 {@code depth_diff} 는 DB 에 남지만 요약 KV 는 없다</li>
 *   <li>최신가 KV</li>
 * </ul>
 * 캔들·파생·펀딩·청산·원시 체결·풀북 원시는 전부 DB 적재 경로라 그대로 남는다.
 */
@Component
public class MarketDataPublisher {

    // 원본과 상수를 맞춰 둔다(하류가 없더라도 키 규약의 기록으로서).
    public static final String KLINE_STREAM_KEY = "market:kline";
    public static final String LIQUIDATION_STREAM_KEY = "market:liquidation";
    public static final String AGG_TRADE_STREAM_KEY = "market:aggTrade";

    public void publishClosedKlines(String symbol, String interval, List<BinanceKline> closedKlines) {
        // no-op
    }

    public void publishAggTrade(String symbol, long aggId, double price, double qty,
                                boolean buyerMaker, long tradeTime) {
        // no-op
    }

    public void publishLiquidations(List<LiquidationEvent> events) {
        // no-op
    }

    public void publishLastPrice(String symbol, double price) {
        // no-op
    }

    public void publishLastPrice(String symbol, BinanceKline latest) {
        // no-op
    }

    public void publishMarkPrice(String symbol, double markPrice, double indexPrice,
                                 double fundingRate, long nextFundingTime, long eventTime) {
        // no-op
    }

    public void publishOrderBookTop(String symbol, double bestBid, double bestAsk,
                                    double bidQty, double askQty, double imbalance, long eventTime) {
        // no-op
    }
}
