-- ===================================================================
-- AI 辅助模块（订单备注结构化解析 + 调用流水）
--
-- 执行方式（PowerShell）：
--   & "E:\MySql\MySQL Server 8.0\bin\mysql.exe" -uroot -p你的密码 sky < sql/ai-module.sql
--
-- 重复执行是安全的：加了存在性判断，可以反复跑。
-- ===================================================================

-- 1) 订单表增加 AI 标签列：存 RemarkIntent 的 JSON 原文
--    用 varchar 而不是单独建表的原因：这个字段只随订单一起读出来展示，
--    没有独立的查询/索引需求，多一张表就多一次 join，收益为负。
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = 'ai_tags'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE orders ADD COLUMN ai_tags varchar(500) DEFAULT NULL COMMENT ''AI 解析订单备注得到的结构化标签(JSON)，识别失败为 null''',
  'SELECT ''orders.ai_tags 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) AI 调用流水表：所有"真实数字"（命中率、降级率、平均延迟、token 成本）的来源
CREATE TABLE IF NOT EXISTS ai_call_log (
  id bigint NOT NULL AUTO_INCREMENT,
  scene varchar(32) NOT NULL COMMENT '场景：remark_parse / insight',
  status varchar(20) NOT NULL COMMENT 'ok / cache_hit / skipped / quota_exceeded / timeout / bad_json / degraded',
  model varchar(40) DEFAULT NULL COMMENT '实际客户端与模型，如 openai-compat(deepseek-flash)',
  prompt_tokens int DEFAULT 0,
  completion_tokens int DEFAULT 0,
  latency_ms int DEFAULT 0 COMMENT '本次模型调用耗时，缓存命中为 0',
  raw_input varchar(255) DEFAULT NULL COMMENT '已截断并脱敏的输入',
  raw_output varchar(1000) DEFAULT NULL COMMENT '模型原始返回，用于事后分析 bad case',
  error_msg varchar(255) DEFAULT NULL COMMENT '降级原因 / 被清洗的越界取值',
  create_time datetime DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_scene_time (scene, create_time),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用流水';

-- 3) 验证：跑完之后用这两条统计自己的真实数字
--    select status, count(*) from ai_call_log where scene='remark_parse' group by status;
--    select avg(latency_ms), sum(prompt_tokens+completion_tokens) from ai_call_log where status='ok';
