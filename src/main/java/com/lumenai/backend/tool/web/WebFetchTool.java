package com.lumenai.backend.tool.web;

import com.lumenai.backend.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebFetch — 抓取指定 URL 的网页内容，返回纯文本
 */
@Slf4j
@Component
public class WebFetchTool implements Tool {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int MAX_CONTENT_LENGTH = 20000;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override public String name() { return "WebFetch"; }
    @Override public String description() { return "Fetch content from a given URL. Returns the page text with HTML tags stripped. Useful for reading articles, docs, or API responses."; }
    @Override public Permission requiredPermission() { return Permission.ASK_USER; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new HashMap<>();
        props.put("url", Map.of("type", "string", "description", "The URL to fetch"));
        props.put("max_length", Map.of("type", "integer", "description", "Max content length in chars (default: 20000)"));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("url"));
        return schema;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "WebFetch",
            description = "Fetch content from a given URL. Returns the page text with HTML tags stripped. Useful for reading articles, docs, or API responses."
    )
    public String fetch(
            @ToolParam(description = "The URL to fetch") String url,
            @ToolParam(description = "Max content length in chars (default: 20000)") Integer max_length) {
        long start = System.currentTimeMillis();
        int maxLen = (max_length != null && max_length > 0) ? max_length : MAX_CONTENT_LENGTH;

        try {
            if (url == null || url.isBlank()) {
                return "ERROR: url is required";
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return "ERROR: URL must start with http:// or https://";
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain,*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Accept-Encoding", "identity")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "ERROR: HTTP " + response.statusCode() + " for " + url;
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String body = response.body();

            if (contentType.contains("application/json")) {
                // JSON 直接返回
                if (body.length() > maxLen) body = body.substring(0, maxLen) + "\n... (truncated)";
                log.info("WebFetch {}: JSON, {} chars, {}ms", url, body.length(), System.currentTimeMillis() - start);
                return body;
            }

            // HTML → 纯文本
            String text = htmlToText(body);
            if (text.length() > maxLen) {
                text = text.substring(0, maxLen) + "\n... (truncated, " + text.length() + " chars total)";
            }
            log.info("WebFetch {}: {} chars text, {}ms", url, text.length(), System.currentTimeMillis() - start);
            return text.isEmpty() ? "(empty page)" : text;

        } catch (Exception e) {
            log.error("WebFetch error for {}", url, e);
            return "ERROR: " + e.getMessage();
        }
    }

    private static final Pattern SCRIPT = Pattern.compile("<script[^>]*>[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE = Pattern.compile("<style[^>]*>[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAV = Pattern.compile("<nav[^>]*>[\\s\\S]*?</nav>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOOTER = Pattern.compile("<footer[^>]*>[\\s\\S]*?</footer>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]+");
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\\n{3,}");

    private String htmlToText(String html) {
        String s = html;
        s = SCRIPT.matcher(s).replaceAll("");
        s = STYLE.matcher(s).replaceAll("");
        s = NAV.matcher(s).replaceAll("");
        s = FOOTER.matcher(s).replaceAll("");
        // 块级标签 → 换行
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|h[1-6]|li|tr|blockquote|pre)>", "\n");
        s = s.replaceAll("(?i)<(p|div|h[1-6]|li|tr|blockquote|pre)[^>]*>", "\n");
        // 去 HTML 标签
        s = TAG.matcher(s).replaceAll("");
        // HTML 实体
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        // 压缩空白
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        s = MULTI_NEWLINE.matcher(s).replaceAll("\n\n");
        return s.trim();
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        try {
            String output = fetch(
                (String) args.get("url"),
                args.get("max_length") != null ? ((Number) args.get("max_length")).intValue() : null
            );
            if (output.startsWith("ERROR:")) {
                return ToolResult.fail(output, (int) (System.currentTimeMillis() - start));
            }
            return ToolResult.ok(output, (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            log.error("WebFetch tool error", e);
            return ToolResult.fail(e.getMessage(), (int) (System.currentTimeMillis() - start));
        }
    }
}
