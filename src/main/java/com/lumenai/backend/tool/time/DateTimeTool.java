package com.lumenai.backend.tool.time;

import com.lumenai.backend.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DateTime — 获取当前日期时间
 * <p>
 * 纯本地计算，零依赖。让 Agent 随时知道 "今天几号 / 现在几点 / 星期几"，
 * 避免模型对时间类问题产生幻觉。
 */
@Slf4j
@Component
public class DateTimeTool implements Tool {

    private static final String DEFAULT_TZ = "Asia/Shanghai";

    @Override
    public String name() { return "DateTime"; }

    @Override
    public String description() {
        return "Get current date, time, day of week, and timezone information. Use when asked 'what time is it', 'what's the date today', 'which day of week', or any time/date related question.";
    }

    @Override
    public Permission requiredPermission() { return Permission.AUTO_ALLOW; }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new HashMap<>();
        props.put("timezone", Map.of("type", "string", "description", "IANA timezone (e.g. 'Asia/Shanghai', 'America/New_York'). Default: Asia/Shanghai"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of());
        return schema;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "DateTime",
            description = "Get current date, time, day of week, and timezone information. Use when asked 'what time is it', 'what's the date today', 'which day of week', or any time/date related question."
    )
    public String now(
            @ToolParam(description = "IANA timezone (e.g. 'Asia/Shanghai', 'America/New_York'). Default: Asia/Shanghai")
            String timezone) {

        String tzId = (timezone == null || timezone.isBlank()) ? DEFAULT_TZ : timezone;
        try {
            ZoneId zone = ZoneId.of(tzId);
            ZonedDateTime zdt = ZonedDateTime.now(zone);

            String[] weekdays = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
            String[] weekdaysZh = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

            StringBuilder sb = new StringBuilder();
            sb.append("Current datetime (").append(tzId).append("):\n");
            sb.append("  ISO 8601: ").append(zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append('\n');
            sb.append("  Date: ").append(zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))).append('\n');
            sb.append("  Time: ").append(zdt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))).append('\n');
            sb.append("  Weekday: ").append(weekdays[zdt.getDayOfWeek().getValue() - 1])
                    .append(" (").append(weekdaysZh[zdt.getDayOfWeek().getValue() - 1]).append(")\n");
            sb.append("  Unix timestamp: ").append(zdt.toEpochSecond()).append('\n');
            sb.append("  DST: ").append(zdt.getZone().getRules().isDaylightSavings(zdt.toInstant()) ? "yes" : "no");

            return sb.toString();

        } catch (Exception e) {
            return "DateTime error: invalid timezone '" + tzId + "'. Use IANA zone like 'Asia/Shanghai'.";
        }
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        String tz = (String) args.get("timezone");
        String output = now(tz);
        if (output.startsWith("DateTime error:")) {
            return ToolResult.fail(output, (int) (System.currentTimeMillis() - start));
        }
        return ToolResult.ok(output, (int) (System.currentTimeMillis() - start));
    }
}
