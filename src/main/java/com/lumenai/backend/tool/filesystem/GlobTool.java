package com.lumenai.backend.tool.filesystem;

import com.lumenai.backend.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Glob — 按文件名模式搜索文件
 */
@Slf4j
@Component
public class GlobTool implements Tool {

    @Override public String name() { return "Glob"; }
    @Override public String description() { return "Find files by name pattern (e.g. \"**/*.java\"). Returns matching file paths."; }
    @Override public Permission requiredPermission() { return Permission.AUTO_ALLOW; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new HashMap<>();
        props.put("pattern", Map.of("type", "string", "description", "Glob pattern (e.g. **/*.java, src/**/*.ts)"));
        props.put("path", Map.of("type", "string", "description", "Base directory to search (default: current directory)"));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("pattern"));
        return schema;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "Glob",
            description = "Find files by name pattern (e.g. \"**/*.java\"). Returns matching file paths."
    )
    public String glob(
            @ToolParam(description = "Glob pattern (e.g. **/*.java, src/**/*.ts)") String pattern,
            @ToolParam(description = "Base directory to search (default: current directory)") String path) {
        long start = System.currentTimeMillis();
        try {
            Path basePath = (path != null && !path.isBlank()) ? Paths.get(path) : Paths.get(".");
            if (!Files.exists(basePath)) {
                return "ERROR: Directory not found: " + basePath;
            }

            String globPattern = pattern.startsWith("/") ? pattern.substring(1) : pattern;
            final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
            List<String> results = new ArrayList<>();

            Files.walkFileTree(basePath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = basePath.relativize(file);
                    if (matcher.matches(file) || matcher.matches(relative)) {
                        results.add(file.toString().replace('\\', '/'));
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

            results.sort(String::compareTo);
            if (results.isEmpty()) {
                return "No files matched pattern: " + pattern;
            }
            log.debug("Glob {}: {} matches, {}ms", pattern, results.size(), System.currentTimeMillis() - start);
            return String.join("\n", results);

        } catch (IOException e) {
            log.error("Glob tool error for {}", pattern, e);
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        try {
            String output = glob((String) args.get("pattern"), (String) args.get("path"));
            if (output.startsWith("ERROR:")) {
                return ToolResult.fail(output, (int) (System.currentTimeMillis() - start));
            }
            return ToolResult.ok(output, (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            log.error("Glob tool error", e);
            return ToolResult.fail(e.getMessage(), (int) (System.currentTimeMillis() - start));
        }
    }
}
