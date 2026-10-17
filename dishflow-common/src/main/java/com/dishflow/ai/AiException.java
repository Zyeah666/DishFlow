package com.dishflow.ai;

/**
 * 大模型调用异常。
 * <p>
 * 刻意区分类型并带上"是否值得重试"标记：
 * 超时、连接失败、5xx 属于瞬时故障，重试一次有意义；
 * 4xx（key 错、参数错、配额耗尽）重试只会再失败一次并再花一笔钱，直接降级。
 * 调用方拿到本异常后不应该往用户上传，而是走降级分支。
 */
public class AiException extends RuntimeException {

    public enum Kind {
        /** 读取超时 */
        TIMEOUT(true),
        /** 连接失败/DNS/网络中断 */
        NETWORK(true),
        /** 服务端 5xx */
        SERVER_ERROR(true),
        /** 4xx：key 无效、参数错误、限流 */
        CLIENT_ERROR(false),
        /** 响应体结构不符合预期 */
        BAD_RESPONSE(false),
        /** 200 但没有内容 */
        EMPTY_CONTENT(false),
        /** 配置了 deepseek 却没填 api-key */
        NOT_CONFIGURED(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }

    private final Kind kind;

    public AiException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isRetryable() {
        return kind.isRetryable();
    }
}
