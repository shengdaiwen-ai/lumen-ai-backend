package com.lumenai.backend.tool.web;

import com.lumenai.backend.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSearch — 博查 Bocha API 为主，百度爬虫兜底
 * <p>
 * 优先使用博查搜索 API（DeepSeek 官方同款搜索引擎，返回干净 JSON），
 * API Key 未配置或调用失败时自动降级到百度爬虫。
 */
@Slf4j
@Component
public class WebSearchTool implements Tool {

    // 博查 API
    private static final String BOCHA_API_URL = "https://api.bochaai.com/v1/web-search";

    // 百度爬虫兜底
    private static final String BAIDU_SEARCH = "https://www.baidu.com/s?wd=%s&rn=10&ie=utf-8";
    private static final String BAIDU_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final Pattern TITLE_LINK = Pattern.compile(
            "<h3[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern SNIPPET_1 = Pattern.compile(
            "<span[^>]+class=\"content-right[^\"]*\"[^>]*>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern SNIPPET_2 = Pattern.compile(
            "<div[^>]+class=\"c-abstract[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern SNIPPET_FALLBACK = Pattern.compile(
            "<p[^>]*>(.*?)</p>", Pattern.DOTALL);

    @Value("${lumen.bocha.api-key:}")
    private String bochaApiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String name() { return "WebSearch"; }

    @Override
    public String description() {
        return "Search the web for real-time information (weather, news, facts, etc). Returns title + URL + snippet for top results.";
    }

    @Override
    public Permission requiredPermission() { return Permission.AUTO_ALLOW; }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new HashMap<>();
        props.put("query", Map.of("type", "string", "description", "Search query (e.g. '郑州今天天气', '今日热搜', 'Spring AI latest version')"));
        props.put("max_results", Map.of("type", "integer", "description", "Max results (default 5, max 10)"));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("query"));
        return schema;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "WebSearch",
            description = "Search the web for real-time information. Use for weather, news, latest info, facts, or anything needing up-to-date data."
    )
    public String search(
            @ToolParam(description = "Search query (e.g. '郑州今天天气', '今日热搜', 'Spring AI最新版本')")
            String query,
            @ToolParam(description = "Max results (default 5, max 10)")
            Integer max_results) {

        long start = System.currentTimeMillis();
        int limit = (max_results != null) ? Math.min(10, Math.max(1, max_results)) : 5;

        // 优先博查 API（有 Key 才用）
        if (bochaApiKey != null && !bochaApiKey.isBlank()) {
            try {
                String result = searchViaBocha(query, limit);
                if (result != null) {
                    log.debug("WebSearch(Bocha) '{}' -> {}ms", query, System.currentTimeMillis() - start);
                    return result;
                }
                log.info("Bocha API failed for '{}', falling back to Baidu", query);
            } catch (Exception e) {
                log.warn("Bocha API error for '{}': {}, falling back to Baidu", query, e.getMessage());
            }
        }

        // 百度爬虫兜底
        try {
            String result = searchViaBaidu(query, limit);
            log.debug("WebSearch(Baidu) '{}' -> {}ms", query, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.warn("Baidu search also failed for '{}': {}", query, e.getMessage());
            return "WebSearch error: All search providers failed for '" + query + "'";
        }
    }

    // ==================== 博查 Bocha API ====================

    private String searchViaBocha(String query, int limit) throws Exception {
        // 构造请求 JSON
        String requestBody = """
                {
                    "query": "%s",
                    "summary": true,
                    "freshness": "noLimit",
                    "count": %d,
                    "page": 1
                }""".formatted(query.replace("\"", "\\\""), limit);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BOCHA_API_URL))
                .header("Authorization", "Bearer " + bochaApiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401) {
            log.error("Bocha API key invalid (401)");
            return null;
        }
        if (response.statusCode() == 403) {
            log.warn("Bocha API balance insufficient (403)");
            return null;
        }
        if (response.statusCode() == 429) {
            log.warn("Bocha API rate limit (429)");
            return null;
        }
        if (response.statusCode() != 200) {
            log.warn("Bocha API HTTP {} for '{}': {}", response.statusCode(), query, response.body());
            return null;
        }

        // 解析 JSON
        String body = response.body();
        List<SearchResult> results = parseBochaJson(body, limit);
        if (results.isEmpty()) {
            return "WebSearch: No results found for '" + query + "'.";
        }
        return formatResults(query, results, limit, "Bocha");
    }

    /**
     * 手动解析博查 JSON — 不引入 Jackson 依赖
     * 格式: {"data":{"webPages":{"value":[{"name":"...","url":"...","snippet":"...","summary":"..."}]}}}
     */
    private List<SearchResult> parseBochaJson(String json, int limit) {
        List<SearchResult> results = new ArrayList<>();

        // 定位 data.webPages.value 数组
        int valueIdx = json.indexOf("\"value\"");
        if (valueIdx < 0) return results;
        int arrayStart = json.indexOf('[', valueIdx);
        if (arrayStart < 0) return results;

        int depth = 0;
        int objStart = -1;
        for (int i = arrayStart; i < json.length() && results.size() < limit; i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String obj = json.substring(objStart, i + 1);
                    String name = extractJsonField(obj, "name");
                    String url = extractJsonField(obj, "url");
                    String snippet = extractJsonField(obj, "summary");
                    if (snippet == null || snippet.isBlank()) {
                        snippet = extractJsonField(obj, "snippet");
                    }
                    if (name != null && url != null) {
                        results.add(new SearchResult(name, url, snippet != null ? snippet : ""));
                    }
                    objStart = -1;
                }
            }
        }
        return results;
    }

    private String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        int end = start;
        while (end < json.length()) {
            if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
            end++;
        }
        if (end >= json.length()) return null;
        String value = json.substring(start, end);
        return value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
    }

    // ==================== 百度爬虫兜底 ====================

    private String searchViaBaidu(String query, int limit) throws Exception {
        String url = String.format(BAIDU_SEARCH, URLEncoder.encode(query, StandardCharsets.UTF_8));

        HttpClient noRedirectClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", BAIDU_USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Encoding", "identity")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = noRedirectClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 302) {
            return "WebSearch: Baidu returned 302 (captcha). Try again later.";
        }
        if (response.statusCode() != 200) {
            return "WebSearch failed: Baidu HTTP " + response.statusCode();
        }

        List<SearchResult> results = parseBaiduHtml(response.body());
        if (results.isEmpty()) {
            return "WebSearch: No results found for '" + query + "'.";
        }

        // 前 3 条百度跳转链接，跟随拿真实 URL
        int resolveLimit = Math.min(limit, 3);
        for (int i = 0; i < resolveLimit; i++) {
            SearchResult r = results.get(i);
            if (r.url.contains("baidu.com/link?url=")) {
                String realUrl = resolveBaiduRedirect(r.url);
                if (realUrl != null) {
                    results.set(i, new SearchResult(r.title, realUrl, r.snippet));
                }
            }
        }
        return formatResults(query, results, limit, "Baidu");
    }

    private List<SearchResult> parseBaiduHtml(String html) {
        List<SearchResult> results = new ArrayList<>();
        Matcher titleMatcher = TITLE_LINK.matcher(html);
        while (titleMatcher.find()) {
            String url = titleMatcher.group(1).trim();
            String title = stripTags(titleMatcher.group(2));
            int afterTitle = titleMatcher.end();
            String contextAfter = html.substring(afterTitle, Math.min(afterTitle + 2000, html.length()));

            String snippet = "";
            Matcher s1 = SNIPPET_1.matcher(contextAfter);
            if (s1.find()) snippet = stripTags(s1.group(1));
            if (snippet.isBlank()) {
                Matcher s2 = SNIPPET_2.matcher(contextAfter);
                if (s2.find()) snippet = stripTags(s2.group(1));
            }
            if (snippet.isBlank()) {
                Matcher sf = SNIPPET_FALLBACK.matcher(contextAfter);
                if (sf.find()) snippet = stripTags(sf.group(1));
            }

            if (title.isBlank() || url.isBlank()) continue;
            if (url.contains("baidu.com/s?") && !url.contains("link?url=")) continue;
            results.add(new SearchResult(title.trim(), url.trim(), snippet.trim()));
        }
        return results;
    }

    private String resolveBaiduRedirect(String baiduLinkUrl) {
        try {
            HttpRequest head = HttpRequest.newBuilder()
                    .uri(URI.create(baiduLinkUrl))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", BAIDU_USER_AGENT)
                    .timeout(Duration.ofSeconds(8))
                    .build();
            HttpResponse<Void> resp = httpClient.send(head, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 300 && resp.statusCode() < 400) {
                return resp.headers().firstValue("Location").orElse(null);
            }
            return baiduLinkUrl;
        } catch (Exception e) {
            return baiduLinkUrl;
        }
    }

    // ==================== 通用工具 ====================

    private String formatResults(String query, List<SearchResult> results, int limit, String provider) {
        StringBuilder sb = new StringBuilder();
        sb.append("WebSearch results for '").append(query).append("' (").append(provider).append("):\n\n");
        int count = 0;
        for (SearchResult r : results) {
            if (count >= limit) break;
            sb.append(count + 1).append(". ").append(r.title).append('\n');
            sb.append("   URL: ").append(r.url).append('\n');
            if (!r.snippet.isBlank()) {
                sb.append("   ").append(r.snippet).append('\n');
            }
            sb.append('\n');
            count++;
        }
        return sb.toString().trim();
    }

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");

    private static String stripTags(String input) {
        if (input == null) return "";
        String s = input.replaceAll("<[^>]+>", "");
        s = s.replace("&nbsp;", " ").replace("&ensp;", " ").replace("&emsp;", " ")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&copy;", "©").replace("&reg;", "®")
                .replace("&trade;", "™").replace("&cent;", "¢")
                .replace("&pound;", "£").replace("&yen;", "¥");
        Matcher numEntity = NUMERIC_ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        while (numEntity.find()) {
            sb.append(s, lastEnd, numEntity.start());
            try {
                int cp = Integer.parseInt(numEntity.group(1));
                sb.append((char) cp);
            } catch (NumberFormatException e) {
                sb.append(numEntity.group());
            }
            lastEnd = numEntity.end();
        }
        sb.append(s.substring(lastEnd));
        s = sb.toString();
        s = s.replaceAll("【[^】]*】", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        try {
            String query = (String) args.get("query");
            Integer limit = args.get("max_results") != null
                    ? ((Number) args.get("max_results")).intValue() : null;
            if (query == null || query.isBlank()) {
                return ToolResult.fail("query is required", (int) (System.currentTimeMillis() - start));
            }
            String output = search(query, limit);
            if (output.startsWith("WebSearch error:")) {
                return ToolResult.fail(output, (int) (System.currentTimeMillis() - start));
            }
            return ToolResult.ok(output, (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage(), (int) (System.currentTimeMillis() - start));
        }
    }

    private record SearchResult(String title, String url, String snippet) {}
}
