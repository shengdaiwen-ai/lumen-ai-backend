package com.lumenai.backend.tool.filesystem;

import com.lumenai.backend.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Grep — 按内容正则搜索文件
 */
@Slf4j
@Component
public class GrepTool implements Tool {

    @Override public String name() { return "Grep"; }
    @Override public String description() { return "Search file contents by regex. Returns matching lines with file paths and line numbers. Useful for finding code, configs, or text patterns."; }
    @Override public Permission requiredPermission() { return Permission.AUTO_ALLOW; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new HashMap<>();
        props.put("pattern", Map.of("type", "string", "description", "Regular expression pattern to search for"));
        props.put("path", Map.of("type", "string", "description", "Directory to search in (default: current directory)"));
        props.put("glob", Map.of("type", "string", "description", "File name pattern filter (e.g. *.java, *.ts). Optional."));
        props.put("case_sensitive", Map.of("type", "boolean", "description", "Case sensitive search (default: false)"));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("pattern"));
        return schema;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "Grep",
            description = "Search file contents by regex. Returns matching lines with file paths and line numbers."
    )
    public String grep(
            @ToolParam(description = "Regular expression pattern to search for") String pattern,
            @ToolParam(description = "Directory to search in (default: current directory)") String path,
            @ToolParam(description = "File name pattern filter (e.g. *.java). Optional.") String glob,
            @ToolParam(description = "Case sensitive search (default: false)") Boolean case_sensitive) {
        long start = System.currentTimeMillis();
        try {
            Path basePath = (path != null && !path.isBlank()) ? Paths.get(path) : Paths.get(".");
            if (!Files.exists(basePath)) {
                return "ERROR: Directory not found: " + basePath;
            }

            int flags = (case_sensitive != null && case_sensitive) ? 0 : Pattern.CASE_INSENSITIVE;
            Pattern regex = Pattern.compile(pattern, flags);
            final PathMatcher fileFilter = (glob != null && !glob.isBlank())
                    ? FileSystems.getDefault().getPathMatcher("glob:" + glob) : null;

            List<String> results = new ArrayList<>();
            Files.walkFileTree(basePath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (fileFilter != null && !fileFilter.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (file.getFileName() != null) {
                        String name = file.getFileName().toString();
                        if (name.equals(".gitignore") || name.startsWith(".")) return FileVisitResult.CONTINUE;
                    }
                    try {
                        List<String> lines = Files.readAllLines(file);
                        for (int i = 0; i < lines.size(); i++) {
                            Matcher m = regex.matcher(lines.get(i));
                            if (m.find()) {
                                results.add(file.toString().replace('\\', '/') + ":" + (i + 1) + ": " + lines.get(i).trim());
                            }
                        }
                    } catch (IOException ignored) {
                        // skip binary or unreadable files
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.getFileName() != null) {
                        String name = dir.getFileName().toString();
                        if (name.equals(".git") || name.equals("node_modules") || name.equals("target") || name.equals("build") || name.equals(".idea")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (results.isEmpty()) {
                return "No matches found for pattern: " + pattern;
            }
            // limit results
            int max = 200;
            String output = results.stream().limit(max).collect(Collectors.joining("\n"));
            if (results.size() > max) {
                output += "\n... (" + results.size() + " total matches, showing first " + max + ")";
            }
            log.debug("Grep {}: {} matches, {}ms", pattern, results.size(), System.currentTimeMillis() - start);
            return output;

        } catch (Exception e) {
            log.error("Grep tool error for {}", pattern, e);
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        try {
            String output = grep(
                (String) args.get("pattern"),
                (String) args.get("path"),
                (String) args.get("glob"),
                args.get("case_sensitive") != null ? ((Boolean) args.get("case_sensitive")) : null
            );
            if (output.startsWith("ERROR:")) {
                return ToolResult.fail(output, (int) (System.currentTimeMillis() - start));
            }
            return ToolResult.ok(output, (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            log.error("Grep tool error", e);
            return ToolResult.fail(e.getMessage(), (int) (System.currentTimeMillis() - start));
        }
    }
}
