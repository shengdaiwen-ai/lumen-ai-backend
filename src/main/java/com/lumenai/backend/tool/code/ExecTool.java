package com.lumenai.backend.tool.code;

import com.lumenai.backend.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Exec — 执行代码片段
 * <p>
 * 支持 Python / JavaScript(Node) / Shell 三种语言。
 * 将代码写入临时文件后执行，返回 stdout + stderr。
 */
@Slf4j
@Component
public class ExecTool implements Tool {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT = 10000;

    @Override public String name() { return "Exec"; }
    @Override public String description() { return "Execute a code snippet (Python, JavaScript, or Shell) and return the output. Useful for calculations, data processing, or quick scripts."; }
    @Override public Permission requiredPermission() { return Permission.ASK_USER; }
    @Override public boolean isReadOnly() { return false; }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new HashMap<>();
        props.put("language", Map.of("type", "string", "description", "Code language: python / javascript / shell"));
        props.put("code", Map.of("type", "string", "description", "The code to execute"));
        props.put("timeout", Map.of("type", "integer", "description", "Timeout in seconds (default: 30)"));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("language", "code"));
        return schema;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "Exec",
            description = "Execute a code snippet (Python, JavaScript, or Shell) and return the output."
    )
    public String exec(
            @ToolParam(description = "Code language: python / javascript / shell") String language,
            @ToolParam(description = "The code to execute") String code,
            @ToolParam(description = "Timeout in seconds (default: 30)") Integer timeout) {
        long start = System.currentTimeMillis();
        int timeoutSec = (timeout != null && timeout > 0) ? timeout : TIMEOUT_SECONDS;

        String lang = language.toLowerCase().trim();

        String fileExt;
        String[] cmdPrefix;
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        switch (lang) {
            case "python":
            case "py":
                fileExt = ".py";
                cmdPrefix = isWindows ? new String[]{"python"} : new String[]{"python3"};
                break;
            case "javascript":
            case "js":
            case "node":
                fileExt = ".js";
                cmdPrefix = new String[]{"node"};
                break;
            case "shell":
            case "sh":
            case "bash":
                fileExt = ".sh";
                cmdPrefix = isWindows ? new String[]{"cmd.exe", "/c"} : new String[]{"bash"};
                break;
            default:
                return "ERROR: Unsupported language: " + language + ". Supported: python, javascript, shell";
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("exec_", fileExt);
            Files.writeString(tempFile, code);

            List<String> cmd = new ArrayList<>(java.util.Arrays.asList(cmdPrefix));
            cmd.add(tempFile.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
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
                return "ERROR: Execution timed out after " + timeoutSec + "s. Partial output:\n" + truncate(output.toString());
            }

            int exitCode = process.exitValue();
            String result = output.toString().trim();
            if (result.length() > MAX_OUTPUT) {
                result = result.substring(0, MAX_OUTPUT) + "\n... (output truncated)";
            }

            log.info("Exec({}): exit={}, {}ms", lang, exitCode, System.currentTimeMillis() - start);

            if (exitCode != 0) {
                return "EXIT CODE " + exitCode + "\n" + (result.isEmpty() ? "(no output)" : result);
            }
            return result.isEmpty() ? "(no output)" : result;

        } catch (IOException e) {
            log.error("Exec tool error", e);
            return "ERROR: " + e.getMessage() + "\nHint: Make sure '" + cmdPrefix[0] + "' is installed and on PATH.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: Execution interrupted";
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    private String truncate(String s) {
        if (s.length() <= MAX_OUTPUT) return s;
        return s.substring(0, MAX_OUTPUT) + "\n... (truncated)";
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        try {
            String output = exec(
                (String) args.get("language"),
                (String) args.get("code"),
                args.get("timeout") != null ? ((Number) args.get("timeout")).intValue() : null
            );
            if (output.startsWith("ERROR:")) {
                return ToolResult.fail(output, (int) (System.currentTimeMillis() - start));
            }
            return ToolResult.ok(output, (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            log.error("Exec tool error", e);
            return ToolResult.fail(e.getMessage(), (int) (System.currentTimeMillis() - start));
        }
    }
}
