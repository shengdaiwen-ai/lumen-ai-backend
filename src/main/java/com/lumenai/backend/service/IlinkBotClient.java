package com.lumenai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * iLink Bot HTTP API 客户端（服务端直连，用于把 AI 回复发回微信）
 * 与 Electron 主进程实现同一套协议。
 */
@Slf4j
@Component
public class IlinkBotClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 随机 X-WECHAT-UIN（4 字节 base64） */
    private String randomUin() {
        byte[] buf = new byte[4];
        RANDOM.nextBytes(buf);
        return Base64.getEncoder().encodeToString(buf);
    }

    /**
     * 发送文本消息
     * @return ret == 0 表示成功
     */
    public boolean sendText(String baseUrl, String botToken, String fromBotId,
                            String toUserId, String text, String contextToken) throws Exception {
        String url = (baseUrl == null || baseUrl.isBlank()
                ? "https://ilinkai.weixin.qq.com" : baseUrl.replaceAll("/+$", ""))
                + "/ilink/bot/sendmessage";

        Map<String, Object> textItem = new LinkedHashMap<>();
        textItem.put("type", 1);
        textItem.put("text_item", Map.of("text", text));

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("from_user_id", fromBotId);
        msg.put("to_user_id", toUserId);
        msg.put("client_id", String.valueOf(System.currentTimeMillis()));
        msg.put("message_type", 2);
        msg.put("message_state", 2);
        msg.put("item_list", java.util.List.of(textItem));
        if (contextToken != null && !contextToken.isBlank()) {
            msg.put("context_token", contextToken);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msg", msg);
        body.put("base_info", Map.of());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("AuthorizationType", "ilink_bot_token")
                .header("Authorization", "Bearer " + botToken)
                .header("X-WECHAT-UIN", randomUin())
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("iLink sendmessage 失败: HTTP {}, body={}", resp.statusCode(), resp.body());
            return false;
        }
        JsonNode node = MAPPER.readTree(resp.body());
        int ret = node.path("ret").asInt(-1);
        if (ret != 0) {
            log.warn("iLink sendmessage ret={}, body={}", ret, resp.body());
        }
        return ret == 0;
    }
}
