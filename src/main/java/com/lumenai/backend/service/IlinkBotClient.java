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
     * 探测 bot_token 是否仍然有效（主动调 iLink 接口，不依赖客户端）。
     *
     * 用 getupdates 短超时探测：token 有效时服务端会挂起连接（读超时属正常，视为有效）；
     * 若返回 ret=-14 / errcode=-14，说明该 bot 已在别处重新绑定，本 token 已作废。
     *
     * @return true=有效；false=已失效
     */
    public boolean probeSession(String baseUrl, String botToken) {
        if (botToken == null || botToken.isBlank()) return false;
        String base = (baseUrl == null || baseUrl.isBlank()) ? "https://ilinkai.weixin.qq.com" : baseUrl;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("get_updates_buf", "");
            body.put("base_info", Map.of("channel_version", "1.0.0"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/ilink/bot/getupdates"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("Authorization", "Bearer " + botToken)
                    .header("X-WECHAT-UIN", randomUin())
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                // 401/403 视为凭据失效；其它 HTTP 错误不轻易判定，避免误杀
                log.warn("iLink 探测返回 HTTP {}", resp.statusCode());
                return resp.statusCode() != 401 && resp.statusCode() != 403;
            }
            JsonNode node = MAPPER.readTree(resp.body());
            int ret = node.path("ret").asInt(node.path("errcode").asInt(0));
            if (ret == -14) {
                log.info("iLink 探测: 会话已失效 ret=-14");
                return false;
            }
            return true;
        } catch (java.net.http.HttpTimeoutException e) {
            // 长轮询挂起导致读超时 = token 有效（服务端在等消息）
            return true;
        } catch (Exception e) {
            // 网络异常无法判定，保守认为有效，避免误清绑定
            log.warn("iLink 探测异常，暂视为有效: {}", e.getMessage());
            return true;
        }
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
