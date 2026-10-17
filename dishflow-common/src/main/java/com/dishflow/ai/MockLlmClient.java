package com.dishflow.ai;

import com.alibaba.fastjson.JSONObject;
import com.dishflow.constant.AiConstant;
import lombok.extern.slf4j.Slf4j;

/**
 * 离线假实现：不联网，按关键词规则返回一个符合 schema 的 JSON。
 * <p>
 * 存在意义有三：
 * 1. 无 key / 断网 / 欠费时项目照样能启动并跑通下单链路（和 MockPayChannel 同理）；
 * 2. 单元测试可以注入它，结果确定，不受模型波动影响；
 * 3. 面试演示时不依赖网络。
 * <p>
 * 它是 mock，不是真的 AI：规则很简单，识别不了否定嵌套和语序歧义，
 * 所以 sky.ai.provider=mock 时评测指标毫无意义，只有 provider=deepseek 时的数字才是真数字。
 */
@Slf4j
public class MockLlmClient implements LlmClient {

    @Override
    public LlmResponse chat(LlmRequest request) {
        long begin = System.currentTimeMillis();
        String content = AiConstant.SCENE_INSIGHT.equals(request.getScene())
                ? mockInsight()
                : mockRemark(request.getUserContent());

        return LlmResponse.builder()
                .content(content)
                .model("mock-rule")
                .finishReason("stop")
                .promptTokens(0)
                .completionTokens(0)
                .latencyMs(System.currentTimeMillis() - begin)
                .build();
    }

    /**
     * 从 <user_input>…</user_input> 里取出原文做关键词匹配。
     */
    private String mockRemark(String wrappedInput) {
        String text = wrappedInput == null ? "" : wrappedInput;
        int s = text.indexOf("<user_input>");
        int e = text.indexOf("</user_input>");
        if (s >= 0 && e > s) {
            text = text.substring(s + "<user_input>".length(), e);
        }

        JSONObject json = new JSONObject();
        json.put("spicy", pickSpicy(text));
        json.put("noIngredients", pickIngredients(text));
        json.put("utensil", pickUtensil(text));
        json.put("deliveryNote", pickDelivery(text));
        json.put("urgent", text.contains("加急") || text.contains("快一点") || text.contains("赶时间"));
        return json.toJSONString();
    }

    private String pickSpicy(String text) {
        // 顺序有讲究：先判"不要辣"再判"辣"，否则"不要辣"会被误判成 more
        if (text.contains("不要辣") || text.contains("不辣") || text.contains("免辣")) {
            return "none";
        }
        if (text.contains("微辣") || text.contains("少辣") || text.contains("有点辣")) {
            return "less";
        }
        if (text.contains("特辣") || text.contains("重辣") || text.contains("多辣")) {
            return "more";
        }
        if (text.contains("正常辣")) {
            return "normal";
        }
        return "unknown";
    }

    private Object pickIngredients(String text) {
        String[] vocab = {"葱", "蒜", "香菜", "花椒", "花生", "芝麻", "姜", "辣椒"};
        java.util.List<String> hit = new java.util.ArrayList<>();
        for (String word : vocab) {
            if (text.contains("不要" + word) || text.contains("不放" + word) || text.contains("免" + word)) {
                hit.add(word);
            }
        }
        return hit;
    }

    private String pickUtensil(String text) {
        if (text.contains("不用餐具") || text.contains("不需要餐具") || text.contains("免餐具")) {
            return "skip";
        }
        if (text.contains("餐具")) {
            return "need";
        }
        return "unknown";
    }

    private String pickDelivery(String text) {
        if (text.contains("前台") || text.contains("保安室") || text.contains("放门口")) {
            return "front_desk";
        }
        if (text.contains("打电话") || text.contains("来电") || text.contains("电话")) {
            return "call";
        }
        if (text.contains("自取") || text.contains("自提") || text.contains("到店")) {
            return "pickup";
        }
        if (text.contains("敲门")) {
            return "knock";
        }
        return "unknown";
    }

    private String mockInsight() {
        JSONObject json = new JSONObject();
        json.put("suggestions", java.util.Arrays.asList(
                "午市主推销量第一的套餐",
                "对营业额最低的工作日做限时满减",
                "把客单价偏低的菜品与热销菜品组套餐"));
        json.put("risk", "订单完成率偏低，需确认是否出餐超时");
        return json.toJSONString();
    }

    @Override
    public String name() {
        return "mock-rule";
    }
}
