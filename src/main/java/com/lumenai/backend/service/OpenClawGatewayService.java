package com.lumenai.backend.service;

import com.lumenai.backend.config.OpenClawProperties;
import com.lumenai.backend.entity.ChannelAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * OpenClaw Gateway HTTP 客户端
 *
 * OpenClaw 微信插件通过以下 API 与 Gateway 通信：
 * - POST getupdates  /  Long-poll 收消息
 * - POST sendmessage  /  发消息
 * - POST sendtyping   /  发送"正在输入"
 * - POST getconfig    /  获取 account 配置
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenClawGatewayService {

    private final OpenClawProperties props;
    private final RestTemplate rest = new RestTemplate();

    // =========== 二维码 ===========

    /** 向 OpenClaw Gateway 请求生成二维码 */
    public String requestQrCode(String channel) {
        String url = props.getGatewayUrl() + "/channels/" + channel + "/qrcode";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<Map> resp = rest.postForEntity(url, new HttpEntity<>(Map.of(), h), Map.class);
            if (resp.getBody() != null && resp.getBody().get("qr") != null) {
                return (String) resp.getBody().get("qr");
            }
            throw new RuntimeException("OpenClaw Gateway 返回空二维码");
        } catch (Exception e) {
            log.error("请求 OpenClaw 二维码失败: {}", e.getMessage());
            throw new RuntimeException("OpenClaw Gateway 不可用（" + props.getGatewayUrl() + "），请确认 Gateway 已启动", e);
        }
    }

    // =========== 发消息 ===========

    public void sendMessage(ChannelAccount account, String peerId, String content) {
        String url = props.getGatewayUrl() + "/channels/" + account.getChannel() + "/sendmessage";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Bearer " + account.getBotToken());
        Map<String, Object> body = Map.of(
                "peerId", peerId,
                "type", "text",
                "content", content
        );
        try {
            rest.postForEntity(url, new HttpEntity<>(body, h), String.class);
        } catch (Exception e) {
            log.error("OpenClaw sendMessage 失败: {}", e.getMessage());
            throw e;
        }
    }

    public void sendTyping(ChannelAccount account, String peerId, boolean on) {
        String url = props.getGatewayUrl() + "/channels/" + account.getChannel() + "/sendtyping";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Bearer " + account.getBotToken());
        Map<String, Object> body = Map.of("peerId", peerId, "typing", on);
        try {
            rest.postForEntity(url, new HttpEntity<>(body, h), String.class);
        } catch (Exception e) {
            log.warn("OpenClaw sendTyping 失败: {}", e.getMessage());
        }
    }

    public void revokeToken(ChannelAccount account) {
        log.info("[OpenClaw] 解绑通道: {}", account.getChannel());
    }

    // =========== Gateway 状态 ===========

    public Map<String, Object> gatewayHealth() {
        try {
            ResponseEntity<Map> resp = rest.getForEntity(props.getGatewayUrl() + "/health", Map.class);
            Map<String, Object> body = resp.getBody() != null ? new LinkedHashMap<>(resp.getBody()) : new LinkedHashMap<>();
            body.put("enabled", true);
            body.put("healthy", true);
            return body;
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("enabled", true);
            err.put("healthy", false);
            err.put("gatewayUrl", props.getGatewayUrl());
            err.put("error", e.getMessage());
            return err;
        }
    }
}
