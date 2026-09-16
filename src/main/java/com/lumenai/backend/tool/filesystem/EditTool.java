package com.lumenai.backend.tool.filesystem;

import com.lumenai.backend.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Edit — 编辑文件指定行或替换文本
 */
@Slf4j
@Component
public class EditTool implements Tool {

    @Override public String name() { return "Edit"; }
    @Override public String description() { return "Edit a file by replacing old text with new text, or insert/delete lines at a specific line number. The file must exist."; }
    @Override public Permission requiredPermission() { return Permission.ASK_USER; }
    @Override public boolean isReadOnly() { return false; }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new HashMap<>();
        props.put("file_path", Map.of("type", "string", "description", "Path to the file to edit"));
        props.put("old_text", Map.of("type", "string", "description", "Text to find and replace (for replace mode)"));
        props.put("new_text", Map.of("type", "string", "description", "Replacement text (for replace mode)"));
        props.put("start_line", Map.of("type", "integer", "description", "Start line number for line-based edit (1-based)"));
        props.put("end_line", Map.of("type", "integer", "description", "End line number for line-based edit (1-based, inclusive)"));
        props.put("new_lines", Map.of("type", "string", "description", "New content to insert at start_line (replaces start_line to end_line)"));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("file_path"));
        return schema;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "Edit",
            description = "Edit a file by replacing old text with new text, or insert/delete lines at a specific line number."
    )
    public String edit(
            @ToolParam(description = "Path to the file to edit") String file_path,
            @ToolParam(description = "Text to find and replace (for replace mode)") String old_text,
            @ToolParam(description = "Replacement text (for replace mode)") String new_text,
            @ToolParam(description = "Start line number for line-based edit (1-based)") Integer start_line,
            @ToolParam(description = "End line number for line-based edit (1-based, inclusive)") Integer end_line,
            @ToolParam(description = "New content to insert at start_line (replaces start_line to end_line)") String new_lines) {
        long start = System.currentTimeMillis();
        try {
            Path path = Paths.get(file_path);
            if (!Files.exists(path)) {
                return "ERROR: File not found: " + path;
            }

            List<String> lines = new ArrayList<>(Files.readAllLines(path));

            // 模式1：文本替换
            if (old_text != null && !old_text.isEmpty() && new_text != null) {
                String fullContent = String.join("\n", lines);
                if (!fullContent.contains(old_text)) {
                    return "ERROR: old_text not found in file: " + file_path;
                }
                int count = fullContent.split(old_text, -1).length - 1;
                if (count > 1) {
                    return "ERROR: old_text appears " + count + " times, expected exactly 1. Use more context.";
                }
                String updated = fullContent.replace(old_text, new_text);
                Files.write(path, updated.getBytes("UTF-8"));
                log.info("Edit {} (replace): {}ms", file_path, System.currentTimeMillis() - start);
                return "OK: replaced text in " + file_path;
            }

            // 模式2：行范围替换
            if (start_line != null && new_lines != null) {
                int startIdx = Math.max(0, start_line - 1);
                int endIdx = end_line != null ? Math.min(lines.size(), end_line) : startIdx + 1;
                String[] replacement = new_lines.split("\n", -1);
                List<String> newContent = new ArrayList<>();
                newContent.addAll(lines.subList(0, startIdx));
                for (String l : replacement) newContent.add(l);
                newContent.addAll(lines.subList(endIdx, lines.size()));
                Files.write(path, newContent);
                log.info("Edit {} (lines {}-{}): {}ms", file_path, start_line, end_line, System.currentTimeMillis() - start);
                return "OK: replaced lines " + start_line + "-" + end_line + " in " + file_path;
            }

            // 模式3：在指定行前插入
            if (start_line != null && old_text == null && new_lines != null) {
                int insertIdx = Math.max(0, Math.min(lines.size(), start_line - 1));
                String[] insertion = new_lines.split("\n", -1);
                List<String> newContent = new ArrayList<>(lines.subList(0, insertIdx));
                for (String l : insertion) newContent.add(l);
                newContent.addAll(lines.subList(insertIdx, lines.size()));
                Files.write(path, newContent);
                log.info("Edit {} (insert at {}): {}ms", file_path, start_line, System.currentTimeMillis() - start);
                return "OK: inserted " + insertion.length + " lines at line " + start_line + " in " + file_path;
            }

            return "ERROR: must provide either (old_text + new_text) or (start_line + new_lines)";

        } catch (IOException e) {
            log.error("Edit tool error for {}", file_path, e);
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        try {
            String output = edit(
                (String) args.get("file_path"),
                (String) args.get("old_text"),
                (String) args.get("new_text"),
                args.get("start_line") != null ? ((Number) args.get("start_line")).intValue() : null,
                args.get("end_line") != null ? ((Number) args.get("end_line")).intValue() : null,
                (String) args.get("new_lines")
            );
            if (output.startsWith("ERROR:")) {
                return ToolResult.fail(output, (int) (System.currentTimeMillis() - start));
            }
            return ToolResult.ok(output, (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            log.error("Edit tool error", e);
            return ToolResult.fail(e.getMessage(), (int) (System.currentTimeMillis() - start));
        }
    }
}
