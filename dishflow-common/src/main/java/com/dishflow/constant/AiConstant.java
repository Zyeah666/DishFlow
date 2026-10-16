package com.dishflow.constant;

/**
 * AI 模块用到的常量：场景名、缓存 key 前缀、调用日志状态。
 */
public final class AiConstant {

    private AiConstant() {
    }

    /** 订单备注结构化解析 */
    public static final String SCENE_REMARK_PARSE = "remark_parse";

    /** 经营报表洞察 */
    public static final String SCENE_INSIGHT = "insight";

    /** 备注解析缓存前缀：ai:remark:{sha1(归一化后的备注)} */
    public static final String CACHE_KEY_REMARK_PREFIX = "ai:remark:";

    /** 每日配额计数 key 前缀：ai:quota:{yyyy-MM-dd} */
    public static final String CACHE_KEY_QUOTA_PREFIX = "ai:quota:";

    /** 调用成功 */
    public static final String STATUS_OK = "ok";
    /** 命中缓存，未花钱 */
    public static final String STATUS_CACHE_HIT = "cache_hit";
    /** 备注为空/被开关关闭，未调用 */
    public static final String STATUS_SKIPPED = "skipped";
    /** 超出每日配额 */
    public static final String STATUS_QUOTA_EXCEEDED = "quota_exceeded";
    /** 模型超时或网络失败，已降级 */
    public static final String STATUS_TIMEOUT = "timeout";
    /** 返回内容非法（非 JSON / 字段越界），已降级 */
    public static final String STATUS_BAD_JSON = "bad_json";
    /** 其它失败，已降级 */
    public static final String STATUS_DEGRADED = "degraded";

    /** 备注缓存时长（天） */
    public static final long REMARK_CACHE_DAYS = 7;
}
