-- depth_diff 의 시간 범위 조회용 BRIN 인덱스 (2026-09-08 운영 DB 에 수동 적용한 것을 마이그레이션으로 편입).
--
-- 배경: 일 파티션에 인덱스가 없어 시간 범위 조회가 파티션 전체(하루 ~1.1억 행) 순차 스캔이었다.
--       btree 는 하루 ~3GB 라 못 쓴다. BRIN 은 파티션당 1~2MB 이고 새 파티션에 자동 전파된다.
--       실측: 20분 청크 조회 14~18s → 1.1s, 1분 0.1s.
-- 되돌리기: DROP INDEX marketdata.depth_diff_event_time_brin;
CREATE INDEX IF NOT EXISTS depth_diff_event_time_brin
    ON depth_diff USING brin (event_time) WITH (pages_per_range = 32);
