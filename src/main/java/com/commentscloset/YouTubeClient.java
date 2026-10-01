package com.commentscloset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Тонкий клиент YouTube Data API v3 (только публичное чтение по API-ключу). */
public class YouTubeClient {
    public static class ApiException extends IOException {
        public final int status;
        public final String reason;
        public ApiException(int status, String reason, String message) {
            super(message);
            this.status = status;
            this.reason = reason;
        }
    }

    public record ChannelInfo(String id, String title, String handle, String uploadsPlaylist) {}

    private static final String BASE = "https://www.googleapis.com/youtube/v3/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String apiKey;

    public YouTubeClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /** Принимает ссылку (@handle, /channel/UC…, /user/…, /c/…), голый @handle или UC-идентификатор. */
    public ChannelInfo resolveChannel(String link) throws IOException, InterruptedException {
        String s = link.trim();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("part", "snippet,contentDetails");

        String path = s;
        int q = s.indexOf("://");
        if (q >= 0) {
            URI uri = URI.create(s);
            path = uri.getPath() == null ? "" : uri.getPath();
        } else if (s.contains("youtube.com/")) {
            path = s.substring(s.indexOf("youtube.com/") + "youtube.com".length());
        }
        path = path.replaceAll("/+$", "");
        String[] seg = path.split("/");
        String last = seg.length > 0 ? seg[seg.length - 1] : "";
        String prev = seg.length > 1 ? seg[seg.length - 2] : "";

        if (prev.equals("channel") || last.matches("UC[\\w-]{22}")) {
            p.put("id", last);
        } else if (prev.equals("user")) {
            p.put("forUsername", last);
        } else if (last.startsWith("@") && last.length() > 1) {
            p.put("forHandle", last);
        } else if (!last.isEmpty()) {
            p.put("forHandle", "@" + last); // /c/name и голые имена — пробуем как handle
        } else {
            throw new IOException("Не удалось распознать ссылку на канал: " + link);
        }

        JsonNode items = get("channels", p).path("items");
        if (items.isEmpty()) throw new IOException("Канал не найден: " + link);
        JsonNode ch = items.get(0);
        return new ChannelInfo(
                ch.path("id").asText(),
                ch.path("snippet").path("title").asText(),
                ch.path("snippet").path("customUrl").asText(null),
                ch.path("contentDetails").path("relatedPlaylists").path("uploads").asText());
    }

    public record Video(String id, String title, String publishedAt) {}

    /** Последние {@code max} видео из плейлиста загрузок (1 единица квоты на 50 видео). */
    public void forEachVideo(String uploadsPlaylist, int max, Consumer<Video> sink) throws IOException, InterruptedException {
        String token = null;
        int n = 0;
        do {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("part", "snippet,contentDetails");
            p.put("playlistId", uploadsPlaylist);
            p.put("maxResults", "50");
            if (token != null) p.put("pageToken", token);
            JsonNode r = get("playlistItems", p);
            for (JsonNode it : r.path("items")) {
                if (n++ >= max) return;
                sink.accept(new Video(
                        it.path("contentDetails").path("videoId").asText(),
                        it.path("snippet").path("title").asText(),
                        it.path("contentDetails").path("videoPublishedAt").asText(null)));
            }
            token = r.path("nextPageToken").asText(null);
        } while (token != null);
    }

    public record Comment(String id, String parentId, String author, String text, int likes,
                          String publishedAt, String updatedAt) {}

    /**
     * Загружает комментарии видео от новых к старым. Если задан {@code stopAt}, останавливается на
     * комментариях верхнего уровня не новее этого времени (инкрементальное обновление).
     */
    public void forEachComment(String videoId, String stopAt, Consumer<Comment> sink)
            throws IOException, InterruptedException {
        String token = null;
        do {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("part", "snippet,replies");
            p.put("videoId", videoId);
            p.put("maxResults", "100");
            p.put("order", "time");
            p.put("textFormat", "plainText");
            if (token != null) p.put("pageToken", token);
            JsonNode r = get("commentThreads", p);
            for (JsonNode thread : r.path("items")) {
                JsonNode top = thread.path("snippet").path("topLevelComment");
                Comment c = toComment(top, null);
                if (stopAt != null && c.publishedAt().compareTo(stopAt) <= 0) return;
                sink.accept(c);
                int total = thread.path("snippet").path("totalReplyCount").asInt();
                JsonNode inline = thread.path("replies").path("comments");
                if (total > inline.size()) {
                    forEachReply(c.id(), sink);
                } else {
                    for (JsonNode rep : inline) sink.accept(toComment(rep, c.id()));
                }
            }
            token = r.path("nextPageToken").asText(null);
        } while (token != null);
    }

    private void forEachReply(String parentId, Consumer<Comment> sink) throws IOException, InterruptedException {
        String token = null;
        do {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("part", "snippet");
            p.put("parentId", parentId);
            p.put("maxResults", "100");
            p.put("textFormat", "plainText");
            if (token != null) p.put("pageToken", token);
            JsonNode r = get("comments", p);
            for (JsonNode rep : r.path("items")) sink.accept(toComment(rep, parentId));
            token = r.path("nextPageToken").asText(null);
        } while (token != null);
    }

    private static Comment toComment(JsonNode node, String parentId) {
        JsonNode sn = node.path("snippet");
        return new Comment(node.path("id").asText(), parentId,
                sn.path("authorDisplayName").asText(""), sn.path("textDisplay").asText(""),
                sn.path("likeCount").asInt(), sn.path("publishedAt").asText(""),
                sn.path("updatedAt").asText(""));
    }

    private JsonNode get(String endpoint, Map<String, String> params) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(BASE).append(endpoint).append("?key=")
                .append(URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
        params.forEach((k, v) -> url.append('&').append(k).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode body = MAPPER.readTree(resp.body());
        if (resp.statusCode() >= 400) {
            JsonNode err = body.path("error");
            String reason = err.path("errors").path(0).path("reason").asText("");
            throw new ApiException(resp.statusCode(), reason, err.path("message").asText("HTTP " + resp.statusCode()));
        }
        return body;
    }
}
