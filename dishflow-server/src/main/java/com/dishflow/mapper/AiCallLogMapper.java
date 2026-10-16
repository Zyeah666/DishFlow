package com.dishflow.mapper;

import com.dishflow.entity.AiCallLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * AI 调用流水表。
 * <p>
 * statByStatus / statCache 是为了"简历上的每个数字都能当场 select 出来"而存在的：
 * 命中率、降级率、平均延迟、token 成本，全部来自真实运行数据而不是估算。
 */
@Mapper
public interface AiCallLogMapper {

    @Insert("insert into ai_call_log (scene, status, model, prompt_tokens, completion_tokens, " +
            "latency_ms, raw_input, raw_output, error_msg, create_time) " +
            "values (#{scene}, #{status}, #{model}, #{promptTokens}, #{completionTokens}, " +
            "#{latencyMs}, #{rawInput}, #{rawOutput}, #{errorMsg}, now())")
    void insert(AiCallLog aiCallLog);

    /**
     * 按状态汇总：看降级率、缓存命中率有多高
     */
    @Select("select status, count(*) as times, avg(latency_ms) as avg_latency_ms, " +
            "sum(prompt_tokens + completion_tokens) as total_tokens " +
            "from ai_call_log where scene = #{scene} group by status")
    List<Map<String, Object>> statByStatus(String scene);

    /**
     * 真实模型调用（排除缓存命中与跳过）的平均延迟与 token 用量
     */
    @Select("select count(*) as times, avg(latency_ms) as avg_latency_ms, " +
            "max(latency_ms) as max_latency_ms, avg(prompt_tokens) as avg_prompt_tokens, " +
            "avg(completion_tokens) as avg_completion_tokens " +
            "from ai_call_log where scene = #{scene} and status in ('ok','bad_json','timeout','degraded')")
    Map<String, Object> statRealCalls(String scene);

    /**
     * 最近的失败样本，用于迭代 few-shot
     */
    @Select("select id, scene, status, model, raw_input, raw_output, error_msg, create_time " +
            "from ai_call_log where status in ('bad_json','timeout','degraded') " +
            "order by create_time desc limit #{limit}")
    List<AiCallLog> listRecentFailures(int limit);
}
