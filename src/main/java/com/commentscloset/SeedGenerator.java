package com.commentscloset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Генератор встроенной БД без GUI: читает настройки из .env, качает комментарии в НОВУЮ базу
 * (пользовательская не затрагивается) и сохраняет её в src/main/resources/seed/comments.db.
 *
 * <pre>java -jar target/comments-closet.jar --generate-seed [путь/к/.env]</pre>
 *
 * Ключи .env: YOUTUBE_API_KEY (обязателен), CHANNELS (ссылки через запятую, обязателен),
 * MAX_VIDEOS (по умолчанию 50), SEED_OUTPUT (по умолчанию src/main/resources/seed/comments.db).
 * Переменные окружения с теми же именами имеют приоритет над файлом (удобно для CI).
 */
public final class SeedGenerator {
    private SeedGenerator() {}

    public static void main(String[] args) {
        try {
            run(Path.of(args.length > 0 ? args[0] : ".env"));
        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
        }
    }

    static void run(Path envFile) throws Exception {
        Map<String, String> env = new HashMap<>(readEnvFile(envFile));
        for (String k : List.of("YOUTUBE_API_KEY", "CHANNELS", "MAX_VIDEOS", "SEED_OUTPUT")) {
            String v = System.getenv(k);
            if (v != null && !v.isBlank()) env.put(k, v);
        }

        String key = env.getOrDefault("YOUTUBE_API_KEY", "").trim();
        List<String> channels = Arrays.stream(env.getOrDefault("CHANNELS", "").split("[,;\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (key.isEmpty()) throw new IllegalArgumentException("не задан YOUTUBE_API_KEY (.env: " + envFile + ")");
        if (channels.isEmpty()) throw new IllegalArgumentException("не задан CHANNELS — ссылки через запятую");
        int maxVideos = Integer.parseInt(env.getOrDefault("MAX_VIDEOS", "" + SyncService.MAX_VIDEOS_PER_CHANNEL).trim());
        Path out = Path.of(env.getOrDefault("SEED_OUTPUT", "src/main/resources/seed/comments.db"));

        Path tmp = Files.createTempFile("seed-", ".db");
        try {
            int total;
            try (Db db = new Db("jdbc:sqlite:" + tmp)) {
                total = new SyncService(db, new YouTubeClient(key), maxVideos)
                        .sync(channels, msg -> System.out.println(msg));
                db.exportTo(out);
            }
            System.out.printf("Готово: %d комментариев, %d каналов → %s (%d КБ)%n",
                    total, channels.size(), out, Files.size(out) / 1024);
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp + "-wal"));
            Files.deleteIfExists(Path.of(tmp + "-shm"));
        }
    }

    private static Map<String, String> readEnvFile(Path file) throws java.io.IOException {
        Map<String, String> m = new HashMap<>();
        if (!Files.exists(file)) return m;
        for (String line : Files.readAllLines(file)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String v = line.substring(eq + 1).trim();
            if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'")))
                v = v.substring(1, v.length() - 1);
            m.put(line.substring(0, eq).trim(), v);
        }
        return m;
    }
}
