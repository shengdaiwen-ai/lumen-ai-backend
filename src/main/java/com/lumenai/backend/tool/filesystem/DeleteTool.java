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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Delete — 删除文件或空目录
 */
@Slf4j
@Component
public class DeleteTool implements Tool {

    @Override public String name() { return "Delete"; }
    @Override public String description() { return "Delete a file or an empty directory. Refuses to delete non-empty directories for safety."; }
    @Override public Permission requiredPermission() { return Permission.ASK_USER; }
    @Override public boolean isReadOnly() { return false; }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new HashMap<>();
        props.put("file_path", Map.of("type", "string", "description", "Path to the file or directory to delete"));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("file_path"));
        return schema;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "Delete",
            description = "Delete a file or an empty directory. Refuses to delete non-empty directories for safety."
    )
    public String delete(
            @ToolParam(description = "Path to the file or directory to delete") String file_path) {
        long start = System.currentTimeMillis();
        try {
            Path path = Paths.get(file_path);
            if (!Files.exists(path)) {
                return "ERROR: Path not found: " + path;
            }

            if (Files.isDirectory(path)) {
                // 安全检查：拒绝删除非空目录
                try (Stream<Path> entries = Files.list(path)) {
                    long count = entries.count();
                    if (count > 0) {
                        return "ERROR: Directory is not empty (" + count + " entries). Refusing to delete non-empty directory for safety.";
                    }
                }
                Files.delete(path);
                log.info("Delete directory: {} ({}ms)", file_path, System.currentTimeMillis() - start);
                return "OK: deleted empty directory " + file_path;
            }

            long size = Files.size(path);
            Files.delete(path);
            log.info("Delete file: {} ({} bytes, {}ms)", file_path, size, System.currentTimeMillis() - start);
            return "OK: deleted file " + file_path + " (" + size + " bytes)";

        } catch (IOException e) {
            log.error("Delete tool error for {}", file_path, e);
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        try {
            String output = delete((String) args.get("file_path"));
            if (output.startsWith("ERROR:")) {
                return ToolResult.fail(output, (int) (System.currentTimeMillis() - start));
            }
            return ToolResult.ok(output, (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            log.error("Delete tool error", e);
            return ToolResult.fail(e.getMessage(), (int) (System.currentTimeMillis() - start));
        }
    }
}
