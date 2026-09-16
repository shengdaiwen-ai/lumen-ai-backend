package com.lumenai.backend.tool.filesystem;

import com.lumenai.backend.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read — 读取文件内容
 * <p>
 * 同时实现自定义 Tool 接口 + Spring AI @Tool 注解，
 * 这样既能被 ToolRegistry 管理（权限控制等），也能被 ChatClient 直接识别。
 */
@Slf4j
@Component
public class ReadTool implements Tool {

    @Override public String name() { return "Read"; }
    @Override public String description() { return "Read the contents of a file. Supports optional line range. Use for understanding code, configs, or any text file."; }
    @Override public Permission requiredPermission() { return Permission.AUTO_ALLOW; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new HashMap<>();
        props.put("file_path", Map.of("type", "string", "description", "Absolute or relative path to the file"));
        props.put("offset", Map.of("type", "integer", "description", "Start line number (1-based, optional)"));
        props.put("limit", Map.of("type", "integer", "description", "Number of lines to read (optional)"));
        props.put("encoding", Map.of("type", "string", "description", "File encoding (default: utf-8)"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("file_path"));
        return schema;
    }

    /**
     * Spring AI @Tool 注解的主方法 — ChatClient 直接调用这个方法
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "Read",
            description = "Read the contents of a file. Supports optional line range. Use for understanding code, configs, or any text file."
    )
    public String read(
            @ToolParam(description = "Absolute or relative path to the file") String file_path,
            @ToolParam(description = "Start line number (1-based, optional)") Integer offset,
            @ToolParam(description = "Number of lines to read (optional)") Integer limit,
            @ToolParam(description = "File encoding (default: utf-8)") String encoding) {
        long start = System.currentTimeMillis();
        try {
            Path path = Paths.get(file_path);
            if (!Files.exists(path)) {
                return "ERROR: File not found: " + path;
            }
            if (!Files.isRegularFile(path)) {
                return "ERROR: Not a regular file: " + path;
            }

            long size = Files.size(path);
            if (size > 5 * 1024 * 1024) {
                return "ERROR: File too large (>5MB): " + path;
            }

            List<String> lines = Files.readAllLines(path);

            if (offset != null || limit != null) {
                int startLine = offset != null ? Math.max(1, offset) : 1;
                int endLine = limit != null ? startLine + limit - 1 : lines.size();
                endLine = Math.min(endLine, lines.size());

                StringBuilder sb = new StringBuilder();
                for (int i = startLine; i <= endLine; i++) {
                    sb.append(i).append(": ").append(lines.get(i - 1)).append('\n');
                }
                log.debug("Read {}: {} lines, {}ms", file_path, (endLine - startLine + 1), System.currentTimeMillis() - start);
                return sb.toString();
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= lines.size(); i++) {
                sb.append(i).append(": ").append(lines.get(i - 1)).append('\n');
            }
            log.debug("Read {}: {} lines, {}ms", file_path, lines.size(), System.currentTimeMillis() - start);
            return sb.toString();

        } catch (IOException e) {
            log.error("Read tool error for {}", file_path, e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Tool 接口的 execute — 保留为兼容路径（非 Spring AI 场景下使用）
     */
    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        try {
            String filePath = (String) args.get("file_path");
            Integer offset = args.get("offset") != null ? ((Number) args.get("offset")).intValue() : null;
            Integer limit = args.get("limit") != null ? ((Number) args.get("limit")).intValue() : null;

            String output = read(filePath, offset, limit, (String) args.getOrDefault("encoding", "utf-8"));

            if (output.startsWith("ERROR:")) {
                return ToolResult.fail(output, (int) (System.currentTimeMillis() - start));
            }
            return ToolResult.ok(output, (int) (System.currentTimeMillis() - start));

        } catch (Exception e) {
            log.error("Read tool error", e);
            return ToolResult.fail(e.getMessage(), (int) (System.currentTimeMillis() - start));
        }
    }
}
