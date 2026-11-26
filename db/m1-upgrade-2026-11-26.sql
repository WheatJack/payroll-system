-- =============================================================
-- 2026-11-26 · M1 后端增量升级
--
-- 对「已经按旧版 schema-mysql.sql 建过表」的环境执行（幂等，可重复跑）。
-- 全新环境直接执行 schema-mysql.sql 即可，不需要本文件。
--
--   1) processing_expire_at 提升到微秒精度
--      任务租约 TTL 可能只有毫秒级；DATETIME 是秒级精度，
--      「已过期」判断会被拖到下一秒，导致快速重试全部失败。
--
--   2) pay_record.version_no 默认值 1 → 0
--      契约统一为「新建记录 version=0，每次 changeset 保存 +1」。
--      保持默认值 1 会让前端拿到的初始版本对不上，第一次保存就 409。
-- =============================================================

ALTER TABLE pay_period
  MODIFY processing_expire_at DATETIME(6) NULL COMMENT '租约过期时间，过期可被抢占';

ALTER TABLE pay_record
  MODIFY version_no INT NOT NULL DEFAULT 0;
