package com.lumenai.backend.tool.shell;

import com.lumenai.backend.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Shell — 执行命令行命令
 */
@Slf4j
@Component
public class ShellTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_LENGTH = 10000;

    @Override public String name() { return "Shell"; }
    @Override public String description() { return "Execute a shell command and return its stdout/stderr output. Use for running build commands, tests, git operations, etc."; }
    @Override public Permission requiredPermission() { return Permission.ASK_USER; }
    @Override public boolean isReadOnly() { return false; }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new HashMap<>();
        props.put("command", Map.of("type", "string", "description", "The shell command to execute"));
        props.put("cwd", Map.of("type", "string", "description", "Working directory (default: current directory)"));
        props.put("timeout", Map.of("type", "integer", "description", "Timeout in seconds (default: 30)"));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("command"));
        return schema;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "Shell",
            description = "Execute a shell command and return its stdout/stderr output. Use for running build commands, tests, git operations, etc."
    )
    public String shell(
            @ToolParam(description = "The shell command to execute") String command,
            @ToolParam(description = "Working directory (default: current directory)") String cwd,
            @ToolParam(description = "Timeout in seconds (default: 30)") Integer timeout) {
        long start = System.currentTimeMillis();
        int timeoutSec = (timeout != null && timeout > 0) ? timeout : DEFAULT_TIMEOUT_SECONDS;

        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb = isWindows
                    ? new ProcessBuilder("cmd.exe", "/c", command)
                    : new ProcessBuilder("bash", "-c", command);

            if (cwd != null && !cwd.isBlank()) {
                pb.directory(new java.io.File(cwd));
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "ERROR: Command timed out after " + timeoutSec + "s. Partial output:\n" + truncate(output.toString());
            }

            int exitCode = process.exitValue();
            String result = output.toString().trim();

            if (result.length() > MAX_OUTPUT_LENGTH) {
                result = result.substring(0, MAX_OUTPUT_LENGTH) + "\n... (output truncated, " + result.length() + " chars total)";
            }

            log.info("Shell '{}': exit={}, {}ms", command, exitCode, System.currentTimeMillis() - start);

            if (exitCode != 0) {
                return "EXIT CODE " + exitCode + "\n" + result;
            }
            return result.isEmpty() ? "(no output)" : result;

        } catch (Exception e) {
            log.error("Shell tool error for command: {}", command, e);
            return "ERROR: " + e.getMessage();
        }
    }

    private String truncate(String s) {
        if (s.length() <= MAX_OUTPUT_LENGTH) return s;
        return s.substring(0, MAX_OUTPUT_LENGTH) + "\n... (truncated)";
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        try {
            String output = shell(
                (String) args.get("command"),
                (String) args.get("cwd"),
                args.get("timeout") != null ? ((Number) args.get("timeout")).intValue() : null
            );
            if (output.startsWith("ERROR:")) {
                return ToolResult.fail(output, (int) (System.currentTimeMillis() - start));
            }
            return ToolResult.ok(output, (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            log.error("Shell tool error", e);
            return ToolResult.fail(e.getMessage(), (int) (System.currentTimeMillis() - start));
        }
    }
}
