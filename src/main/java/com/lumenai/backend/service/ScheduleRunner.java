package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumenai.backend.agent.core.AgentEngine;
import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.dto.ChatMessageRequest;
import com.lumenai.backend.dto.SessionCreateRequest;
import com.lumenai.backend.entity.Message;
import com.lumenai.backend.entity.Schedule;
import com.lumenai.backend.entity.Session;
import com.lumenai.backend.mapper.MessageMapper;
import com.lumenai.backend.mapper.ScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 自动化任务定时调度器
 * <p>
 * 每分钟扫描一次，找出到达执行时间的 active 任务并执行。
 * 执行记录写入 schedule_runs，trigger_type=auto。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleRunner {

    private final ScheduleMapper scheduleMapper;
    private final ScheduleRunService scheduleRunService;
    private final SessionService sessionService;
    private final MessageMapper messageMapper;
    private final AgentEngine agentEngine;
    private final ObjectMapper objectMapper;

    /** 每分钟扫描一次到期任务 */
    @Scheduled(fixedDelay = 60_000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        List<Schedule> due = scheduleMapper.selectList(
                new LambdaQueryWrapper<Schedule>()
                        .eq(Schedule::getStatus, "active")
                        .isNotNull(Schedule::getNextRunAt)
                        .le(Schedule::getNextRunAt, now)
        );
        if (due.isEmpty()) return;

        log.info("ScheduleRunner: 发现 {} 个到期任务", due.size());
        for (Schedule s : due) {
            try {
                executeSchedule(s);
            } catch (Exception e) {
                log.error("执行自动化任务失败 id={}, name={}", s.getId(), s.getName(), e);
            }
        }
    }

    /**
     * 执行单个自动化任务：
     * 1. 创建执行记录（auto, running）
     * 2. 创建会话 + 写入 user message
     * 3. 调用 AgentEngine 执行
     * 4. 根据结果更新执行记录状态 + 计算下次执行时间
     */
    private void executeSchedule(Schedule schedule) {
        Long userId = schedule.getUserId();
        Long runId = scheduleRunService.startRun(schedule.getId(), userId, "auto").getId();

        // 更新 lastRunAt
        schedule.setLastRunAt(LocalDateTime.now());
        schedule.setUpdatedAt(LocalDateTime.now());
        scheduleMapper.updateById(schedule);

        // 创建会话并写入用户消息
        SessionCreateRequest scr = new SessionCreateRequest();
        scr.setTitle(schedule.getName());
        Session session = sessionService.create(userId, scr);

        // 关联 session_id 到执行记录
        scheduleRunService.updateSessionId(runId, session.getId());

        Message userMsg = new Message();
        userMsg.setSessionId(session.getId());
        userMsg.setUserId(userId);
        userMsg.setRole("user");
        userMsg.setContent(schedule.getMessage());
        userMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(userMsg);

        UserContext.set(userId);
        try {
            ChatMessageRequest req = new ChatMessageRequest();
            req.setContent(schedule.getMessage());

            Flux<ServerSentEvent<String>> flux = agentEngine.run(session, req);

            // 订阅流，收集 done 事件
            flux.subscribe(
                    event -> {
                        if ("done".equals(event.event()) && event.data() != null) {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> payload = objectMapper.readValue(event.data(), Map.class);
                                String finalContent = (String) payload.getOrDefault("finalContent", "");
                                String summary = finalContent.length() > 200
                                        ? finalContent.substring(0, 200) + "..."
                                        : finalContent;
                                scheduleRunService.completeRun(runId, "success", summary, null);
                                log.info("自动化任务执行成功 scheduleId={}, runId={}", schedule.getId(), runId);
                            } catch (Exception e) {
                                scheduleRunService.completeRun(runId, "failed", null, e.getMessage());
                            }
                        } else if ("error".equals(event.event())) {
                            scheduleRunService.completeRun(runId, "failed", null, event.data());
                        }
                    },
                    err -> scheduleRunService.completeRun(runId, "failed", null, err.getMessage())
            );
        } catch (Exception e) {
            scheduleRunService.completeRun(runId, "failed", null, e.getMessage());
        } finally {
            UserContext.clear();
        }

        // 计算并更新下次执行时间
        LocalDateTime next = ScheduleService.computeNextRun(schedule, LocalDateTime.now());
        schedule.setNextRunAt(next);
        schedule.setUpdatedAt(LocalDateTime.now());
        scheduleMapper.updateById(schedule);
        log.info("自动化任务 scheduleId={} 下次执行时间: {}", schedule.getId(), next);
    }
}
