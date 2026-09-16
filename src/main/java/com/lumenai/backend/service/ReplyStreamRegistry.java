package com.lumenai.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通道 AI 回复的流式进度注册表（内存态）
 *
 * key = accountId + ":" + peerId
 * 后端生成回复时不断 append 分片文本，前端通过 SSE 轮询该状态实现逐字输出。
 * 回复完成后保留 60 秒（供迟到的订阅者拿到最终内容），然后清理。
 */
@Slf4j
@Component
public class ReplyStreamRegistry {

    public static class State {
        private final StringBuilder partial = new StringBuilder();
        private volatile long version = 0;
        private volatile boolean done = false;
        private volatile boolean active = false;
        private volatile String error = null;
        /** 本次回复消耗的 token（供 SSE done 事件下发） */
        private volatile Integer inputTokens = null;
        private volatile Integer outputTokens = null;

        public synchronized void append(String delta) {
            if (delta != null && !delta.isEmpty()) {
                partial.append(delta);
                version++;
            }
        }

        public synchronized String snapshot() { return partial.toString(); }
        public synchronized long getVersion() { return version; }
        public boolean isDone() { return done; }
        public boolean isActive() { return active; }
        public String getError() { return error; }
        public Integer getInputTokens() { return inputTokens; }
        public Integer getOutputTokens() { return outputTokens; }

        public void setUsage(Integer inputTokens, Integer outputTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public static String key(Long accountId, String peerId) {
        return accountId + ":" + peerId;
    }

    public State start(String key) {
        State s = new State();
        s.active = true;
        states.put(key, s);
        return s;
    }

    public State get(String key) {
        return states.get(key);
    }

    public void finish(String key) {
        State s = states.get(key);
        if (s != null) {
            synchronized (s) {
                s.active = false;
                s.done = true;
                s.version++;
                s.notifyAll();
            }
            // 60 秒后清理，给迟到订阅者留窗口
            new Thread(() -> {
                try { Thread.sleep(60_000); } catch (InterruptedException ignored) {}
                states.remove(key);
            }, "reply-stream-cleanup").start();
        }
    }

    public void fail(String key, String error) {
        State s = states.get(key);
        if (s != null) {
            synchronized (s) {
                s.active = false;
                s.done = true;
                s.error = error;
                s.version++;
                s.notifyAll();
            }
        }
    }
}
