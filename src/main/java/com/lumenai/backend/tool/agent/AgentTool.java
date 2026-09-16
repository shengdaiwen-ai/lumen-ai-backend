package com.lumenai.backend.tool.agent;

import com.lumenai.backend.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Agent — 启动子 Agent 执行复杂任务
 * 使用 Spring AI ChatClient 独立调用 LLM，不带工具，避免递归。
 */
@Slf4j
@Component
public class AgentTool implements Tool {

    private final ChatClient.Builder chatClientBuilder;

    public AgentTool(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    @Override public String name() { return "Agent"; }
    @Override public String description() { return "Launch a sub-agent to handle a complex task independently. The sub-agent will think step by step and return a result. Use for research, multi-step reasoning, or task decomposition."; }
    @Override public Permission requiredPermission() { return Permission.ASK_USER; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new HashMap<>();
        props.put("task", Map.of("type", "string", "description", "The task description for the sub-agent to execute"));
        props.put("context", Map.of("type", "string", "description", "Additional context or background info (optional)"));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("task"));
        return schema;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "Agent",
            description = "Launch a sub-agent to handle a complex task independently. The sub-agent will think step by step and return a result."
    )
    public String agent(
            @ToolParam(description = "The task description for the sub-agent to execute") String task,
            @ToolParam(description = "Additional context or background info (optional)") String context) {
        long start = System.currentTimeMillis();
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("You are a helpful sub-agent. Complete the following task concisely. Return only the result.\n\n");
            if (context != null && !context.isBlank()) {
                prompt.append("Context:\n").append(context).append("\n\n");
            }
            prompt.append("Task:\n").append(task).append("\n");

            String result = chatClientBuilder.build()
                    .prompt()
                    .user(prompt.toString())
                    .call()
                    .content();

            if (result == null || result.isBlank()) {
                return "(sub-agent returned empty result)";
            }
            if (result.length() > 5000) {
                result = result.substring(0, 5000) + "\n... (truncated)";
            }
            log.info("AgentTool '{}': {}ms, {} chars", task.substring(0, Math.min(50, task.length())), System.currentTimeMillis() - start, result.length());
            return result;
        } catch (Exception e) {
            log.error("AgentTool error for task: {}", task, e);
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        try {
            String output = agent(
                (String) args.get("task"),
                (String) args.get("context")
            );
            if (output.startsWith("ERROR:")) {
                return ToolResult.fail(output, (int) (System.currentTimeMillis() - start));
            }
            return ToolResult.ok(output, (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            log.error("AgentTool error", e);
            return ToolResult.fail(e.getMessage(), (int) (System.currentTimeMillis() - start));
        }
    }
}
