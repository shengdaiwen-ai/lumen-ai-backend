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
 * Write — 创建或覆盖写入文件
 */
@Slf4j
@Component
public class WriteTool implements Tool {

    @Override public String name() { return "Write"; }
    @Override public String description() { return "Create a new file or overwrite an existing file with the given content. Creates parent directories if needed."; }
    @Override public Permission requiredPermission() { return Permission.ASK_USER; }
    @Override public boolean isReadOnly() { return false; }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new HashMap<>();
        props.put("file_path", Map.of("type", "string", "description", "Path to the file to write"));
        props.put("content", Map.of("type", "string", "description", "Full content to write to the file"));
        props.put("encoding", Map.of("type", "string", "description", "File encoding (default: utf-8)"));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("file_path", "content"));
        return schema;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "Write",
            description = "Create a new file or overwrite an existing file with the given content. Creates parent directories if needed."
    )
    public String write(
            @ToolParam(description = "Path to the file to write") String file_path,
            @ToolParam(description = "Full content to write to the file") String content,
            @ToolParam(description = "File encoding (default: utf-8)") String encoding) {
        long start = System.currentTimeMillis();
        try {
            Path path = Paths.get(file_path);

            // 创建父目录
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                log.info("Created directories: {}", parent);
            }

            String enc = (encoding != null && !encoding.isBlank()) ? encoding : "UTF-8";
            Files.write(path, content.getBytes(enc));

            int lines = content.split("\n", -1).length;
            log.info("Write {}: {} bytes, {} lines, {}ms", file_path, content.length(), lines, System.currentTimeMillis() - start);
            return "OK: wrote " + content.length() + " bytes (" + lines + " lines) to " + file_path;

        } catch (IOException e) {
            log.error("Write tool error for {}", file_path, e);
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        try {
            String output = write(
                (String) args.get("file_path"),
                (String) args.get("content"),
                (String) args.get("encoding")
            );
            if (output.startsWith("ERROR:")) {
                return ToolResult.fail(output, (int) (System.currentTimeMillis() - start));
            }
            return ToolResult.ok(output, (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            log.error("Write tool error", e);
            return ToolResult.fail(e.getMessage(), (int) (System.currentTimeMillis() - start));
        }
    }
}
