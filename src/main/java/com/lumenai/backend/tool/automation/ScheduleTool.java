package com.lumenai.backend.tool.automation;

import com.lumenai.backend.agent.core.AgentContext;
import com.lumenai.backend.agent.tool.Tool;
import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.dto.ScheduleCreateRequest;
import com.lumenai.backend.dto.ScheduleResponse;
import com.lumenai.backend.entity.Schedule;
import com.lumenai.backend.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ScheduleTool — 自动化任务管理工具
 * <p>
 * 让 AI 在对话中创建、查询自动化任务。
 * 用户说"每天9点执行冒泡排序练习"时，AI 自动提取参数并调用此工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleTool implements Tool {

    private final ScheduleService scheduleService;

    @Override
    public String name() { return "ScheduleTool"; }

    @Override
    public String description() {
        return "Create and manage automation tasks (schedules). Use when the user wants to set up a recurring task. "
                + "Supports daily, weekly, monthly, and interval triggers. "
                + "Example: '每天9点执行XX' → frequency=每天, runHour=9, runMinute=0";
    }

    @Override
    public Permission requiredPermission() { return Permission.AUTO_ALLOW; }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new HashMap<>();
        props.put("action", Map.of("type", "string",
                "description", "Action to perform: 'create' or 'list'",
                "enum", List.of("create", "list")));
        props.put("name", Map.of("type", "string",
                "description", "Task name (required for create)"));
        props.put("frequency", Map.of("type", "string",
                "description", "Trigger frequency: 每天/每周/每月/间隔触发",
                "enum", List.of("每天", "每周", "每月", "间隔触发")));
        props.put("message", Map.of("type", "string",
                "description", "Task content/prompt — what the AI should do when this task runs"));
        props.put("runHour", Map.of("type", "integer",
                "description", "Execution hour (0-23). Default 9"));
        props.put("runMinute", Map.of("type", "integer",
                "description", "Execution minute (0-59). Default 0"));
        props.put("weekday", Map.of("type", "string",
                "description", "For weekly: comma-separated weekday numbers 1-7 (1=Monday). E.g. '1,3,5' for Mon/Wed/Fri"));
        props.put("monthDay", Map.of("type", "integer",
                "description", "For monthly: day of month (1-31). Default 1"));
        props.put("intervalValue", Map.of("type", "integer",
                "description", "For interval: the interval value. E.g. every 2 hours → 2"));
        props.put("intervalUnit", Map.of("type", "string",
                "description", "For interval: unit — 分钟/小时/天",
                "enum", List.of("分钟", "小时", "天")));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("action"));
        return schema;
    }

    // ========== Spring AI @Tool 方法（供 ChatModel 调用） ==========

    @org.springframework.ai.tool.annotation.Tool(
            name = "createSchedule",
            description = "Create an automation task. Call this when the user wants to schedule a recurring task. "
                    + "Extract task name, frequency, time, and content from the conversation. "
                    + "Frequencies: 每天/每周/每月/间隔触发. "
                    + "For weekly, provide weekday (e.g. '1,3,5'). For monthly, provide monthDay. For interval, provide intervalValue and intervalUnit."
    )
    public String createSchedule(
            @ToolParam(description = "Task name") String name,
            @ToolParam(description = "Trigger frequency: 每天/每周/每月/间隔触发") String frequency,
            @ToolParam(description = "Task content — what the AI should do when this task runs") String message,
            @ToolParam(description = "Execution hour 0-23, default 9") Integer runHour,
            @ToolParam(description = "Execution minute 0-59, default 0") Integer runMinute,
            @ToolParam(description = "For weekly: comma-separated weekday numbers 1-7 (1=Mon). E.g. '1,3,5'") String weekday,
            @ToolParam(description = "For monthly: day of month 1-31") Integer monthDay,
            @ToolParam(description = "For interval: the value, e.g. 2 for every 2 hours") Integer intervalValue,
            @ToolParam(description = "For interval: unit — 分钟/小时/天") String intervalUnit
    ) {
        long start = System.currentTimeMillis();
        Long userId = UserContext.get();
        if (userId == null) {
            return "错误：未登录，无法创建自动化任务";
        }

        try {
            ScheduleCreateRequest req = new ScheduleCreateRequest();
            req.setName(name);
            req.setFrequency(frequency != null ? frequency : "每天");
            req.setMessage(message);
            req.setRunHour(runHour != null ? runHour : 9);
            req.setRunMinute(runMinute != null ? runMinute : 0);
            req.setWeekday(weekday);
            req.setMonthDay(monthDay);
            req.setIntervalValue(intervalValue);
            req.setIntervalUnit(intervalUnit);
            req.setTimezone("Asia/Shanghai");
            // 继承「当前这次对话」的空间：模型不可能知道空间 id，只能由运行上下文透传。
            // 不传的话任务建出来就是无空间的，定时跑起来碰不了任何文件。
            req.setWorkspaceId(AgentContext.getWorkspaceId());

            Schedule created = scheduleService.create(userId, req);
            int duration = (int) (System.currentTimeMillis() - start);

            // 无空间时明确提醒：任务建成后靠 AgentEngine 剔除本地类工具来「兜底」，
            // 但用户看到的是一次成功的创建 —— 不提醒的话，他要等到任务跑完才发现文件没动过。
            String tail = req.getWorkspaceId() == null
                    ? "\n⚠️ 本次对话未绑定工作空间，该任务运行时无法读写本机文件。"
                    + "如需读写文件，请先在输入框左下角选择工作空间，再重新创建任务。"
                    : "";

            return String.format(
                    "自动化任务创建成功！\n任务ID: %d\n名称: %s\n频率: %s\n执行时间: %02d:%02d\n内容: %s%s",
                    created.getId(), created.getName(), created.getFrequency(),
                    created.getRunHour(), created.getRunMinute(), created.getMessage(), tail
            );
        } catch (Exception e) {
            log.error("createSchedule failed", e);
            return "创建失败：" + e.getMessage();
        }
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "listSchedules",
            description = "List all automation tasks for the current user. Call this when the user asks to see their scheduled tasks."
    )
    public String listSchedules() {
        long start = System.currentTimeMillis();
        Long userId = UserContext.get();
        if (userId == null) {
            return "错误：未登录，无法查询自动化任务";
        }

        try {
            List<ScheduleResponse> list = scheduleService.listByUser(userId);
            int duration = (int) (System.currentTimeMillis() - start);

            if (list.isEmpty()) {
                return "您还没有任何自动化任务。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("共 %d 个自动化任务：\n", list.size()));
            for (ScheduleResponse s : list) {
                sb.append(String.format("  [%d] %s | %s %02d:%02d | 状态: %s | 内容: %s\n",
                        s.getId(), s.getName(), s.getFrequency(),
                        s.getRunHour(), s.getRunMinute(), s.getStatus(), s.getMessage()));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("listSchedules failed", e);
            return "查询失败：" + e.getMessage();
        }
    }

    // ========== 自定义 Tool 接口实现 ==========

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        String action = (String) args.get("action");

        try {
            String output;
            if ("list".equals(action)) {
                output = listSchedules();
            } else {
                // create
                ScheduleCreateRequest req = new ScheduleCreateRequest();
                req.setName((String) args.get("name"));
                req.setFrequency((String) args.get("frequency"));
                req.setMessage((String) args.get("message"));
                req.setRunHour(args.get("runHour") != null ? ((Number) args.get("runHour")).intValue() : 9);
                req.setRunMinute(args.get("runMinute") != null ? ((Number) args.get("runMinute")).intValue() : 0);
                req.setWeekday((String) args.get("weekday"));
                req.setMonthDay(args.get("monthDay") != null ? ((Number) args.get("monthDay")).intValue() : null);
                req.setIntervalValue(args.get("intervalValue") != null ? ((Number) args.get("intervalValue")).intValue() : null);
                req.setIntervalUnit((String) args.get("intervalUnit"));
                req.setTimezone("Asia/Shanghai");
                // 与 createSchedule 同理：继承当前运行的空间
                req.setWorkspaceId(AgentContext.getWorkspaceId());

                Long userId = UserContext.get();
                if (userId == null) {
                    return ToolResult.fail("未登录", (int) (System.currentTimeMillis() - start));
                }
                Schedule created = scheduleService.create(userId, req);
                output = String.format("任务创建成功！ID=%d, 名称=%s", created.getId(), created.getName());
            }
            return ToolResult.ok(output, (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage(), (int) (System.currentTimeMillis() - start));
        }
    }
}
