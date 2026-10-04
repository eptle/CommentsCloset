package com.commentscloset;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** SQLite-хранилище каналов, видео и комментариев. Даты — ISO-8601 строки (сортируются лексикографически). */
public class Db implements AutoCloseable {
    public record Channel(String id, String title) {
        @Override public String toString() { return title; }
    }

    public record CommentRow(String id, String channelTitle, String videoTitle, String videoId,
                             String author, String text, int likes, String publishedAt, boolean reply) {}

    private final Connection conn;

    /** Основная БД пользователя. При первом запуске копируется из встроенной заготовки /seed/comments.db (если есть). */
    public Db() {
        this("jdbc:sqlite:" + prepareUserDb());
    }

    private static java.nio.file.Path prepareUserDb() {
        java.nio.file.Path file = AppPaths.dataDir().resolve("comments.db");
        if (!java.nio.file.Files.exists(file)) {
            try (java.io.InputStream in = Db.class.getResourceAsStream("/seed/comments.db")) {
                if (in != null) java.nio.file.Files.copy(in, file);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Не удалось развернуть встроенную БД", e);
            }
        }
        return file;
    }

    /** Сохраняет компактную копию БД одним файлом (для встраивания в сборку). */
    public synchronized void exportTo(java.nio.file.Path target) {
        try {
            java.nio.file.Files.deleteIfExists(target);
            if (target.getParent() != null) java.nio.file.Files.createDirectories(target.getParent());
            try (Statement st = conn.createStatement()) {
                st.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось экспортировать БД", e);
        }
    }

    /** Ссылки на каналы из БД — чтобы заполнить поле настроек у нового пользователя. */
    public synchronized List<String> channelLinks() {
        List<String> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id,handle FROM channels ORDER BY title")) {
            while (rs.next()) {
                String handle = rs.getString(2);
                list.add("https://www.youtube.com/" + (handle != null && handle.startsWith("@")
                        ? handle : "channel/" + rs.getString(1)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return list;
    }

    public Db(String url) {
        try {
            conn = DriverManager.getConnection(url);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("""
                    CREATE TABLE IF NOT EXISTS channels(
                        id TEXT PRIMARY KEY, title TEXT NOT NULL, handle TEXT, uploads_playlist TEXT)""");
                st.execute("""
                    CREATE TABLE IF NOT EXISTS videos(
                        id TEXT PRIMARY KEY, channel_id TEXT NOT NULL REFERENCES channels(id),
                        title TEXT NOT NULL, published_at TEXT)""");
                st.execute("""
                    CREATE TABLE IF NOT EXISTS comments(
                        id TEXT PRIMARY KEY, video_id TEXT NOT NULL REFERENCES videos(id),
                        parent_id TEXT, author TEXT, text TEXT, like_count INTEGER,
                        published_at TEXT, updated_at TEXT)""");
                st.execute("CREATE INDEX IF NOT EXISTS idx_comments_video ON comments(video_id, published_at)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_comments_pub ON comments(published_at)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Ошибка открытия БД", e);
        }
    }

    public synchronized void upsertChannel(String id, String title, String handle, String uploads) {
        exec("""
            INSERT INTO channels(id,title,handle,uploads_playlist) VALUES(?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET title=excluded.title, handle=excluded.handle,
                uploads_playlist=excluded.uploads_playlist""", id, title, handle, uploads);
    }

    public synchronized void upsertVideo(String id, String channelId, String title, String publishedAt) {
        exec("""
            INSERT INTO videos(id,channel_id,title,published_at) VALUES(?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET title=excluded.title""", id, channelId, title, publishedAt);
    }

    public synchronized void upsertComment(String id, String videoId, String parentId, String author,
                                           String text, int likes, String publishedAt, String updatedAt) {
        exec("""
            INSERT INTO comments(id,video_id,parent_id,author,text,like_count,published_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET text=excluded.text, like_count=excluded.like_count,
                updated_at=excluded.updated_at""",
            id, videoId, parentId, author, text, likes, publishedAt, updatedAt);
    }

    public synchronized void inTransaction(Runnable r) {
        try {
            conn.setAutoCommit(false);
            try {
                r.run();
                conn.commit();
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Время самого свежего сохранённого комментария видео (или null) — для инкрементальной загрузки. */
    public synchronized String latestCommentTime(String videoId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT MAX(published_at) FROM comments WHERE video_id=? AND parent_id IS NULL")) {
            ps.setString(1, videoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized List<Channel> channels() {
        List<Channel> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id,title FROM channels ORDER BY title")) {
            while (rs.next()) list.add(new Channel(rs.getString(1), rs.getString(2)));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return list;
    }

    /** @param channelId null — все каналы; @param search подстрока текста/автора (может быть пустой). */
    public synchronized List<CommentRow> comments(String channelId, String search, int limit, boolean random) {
        StringBuilder sql = new StringBuilder("""
            SELECT c.id, ch.title, v.title, v.id, c.author, c.text, c.like_count, c.published_at, c.parent_id
            FROM comments c JOIN videos v ON v.id=c.video_id JOIN channels ch ON ch.id=v.channel_id
            WHERE 1=1""");
        List<String> args = new ArrayList<>();
        if (channelId != null) { sql.append(" AND ch.id=?"); args.add(channelId); }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (c.text LIKE ? ESCAPE '\\' OR c.author LIKE ? ESCAPE '\\')");
            String like = "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like); args.add(like);
        }
        sql.append(random ? " ORDER BY RANDOM()" : " ORDER BY c.published_at DESC").append(" LIMIT ?");
        List<CommentRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            for (String a : args) ps.setString(i++, a);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new CommentRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8),
                            rs.getString(9) != null));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    public synchronized int count() {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM comments")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void exec(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
