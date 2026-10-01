package com.commentscloset;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Загружает комментарии каналов из YouTube в БД. Повторный запуск докачивает только новое. */
public class SyncService {
    public static final int MAX_VIDEOS_PER_CHANNEL = 50;

    private final Db db;
    private final YouTubeClient yt;

    public SyncService(Db db, YouTubeClient yt) {
        this.db = db;
        this.yt = yt;
    }

    /** @return число новых/обновлённых комментариев. */
    public int sync(List<String> channelLinks, Consumer<String> progress) throws Exception {
        AtomicInteger total = new AtomicInteger();
        for (String link : channelLinks) {
            if (link.isBlank()) continue;
            try {
                syncChannel(link, progress, total);
            } catch (YouTubeClient.ApiException e) {
                if (isFatal(e)) throw e;
                progress.accept("Канал " + link + ": " + e.getMessage());
            } catch (java.io.IOException e) {
                progress.accept("Канал " + link + ": " + e.getMessage());
            }
        }
        return total.get();
    }

    private void syncChannel(String link, Consumer<String> progress, AtomicInteger total) throws Exception {
        progress.accept("Ищу канал " + link + " …");
        YouTubeClient.ChannelInfo ch = yt.resolveChannel(link);
        db.upsertChannel(ch.id(), ch.title(), ch.handle(), ch.uploadsPlaylist());

        List<YouTubeClient.Video> videos = new java.util.ArrayList<>();
        yt.forEachVideo(ch.uploadsPlaylist(), MAX_VIDEOS_PER_CHANNEL, videos::add);

        int i = 0;
        for (YouTubeClient.Video v : videos) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            progress.accept("%s: видео %d/%d — %s".formatted(ch.title(), ++i, videos.size(), v.title()));
            db.upsertVideo(v.id(), ch.id(), v.title(), v.publishedAt());
            try {
                String stopAt = db.latestCommentTime(v.id());
                // собираем в память и пишем одной транзакцией — быстро и без «половинных» видео
                List<YouTubeClient.Comment> batch = new java.util.ArrayList<>();
                yt.forEachComment(v.id(), stopAt, batch::add);
                db.inTransaction(() -> batch.forEach(c -> db.upsertComment(c.id(), v.id(), c.parentId(),
                        c.author(), c.text(), c.likes(), c.publishedAt(), c.updatedAt())));
                total.addAndGet(batch.size());
            } catch (YouTubeClient.ApiException e) {
                if (isFatal(e)) throw e;
                // commentsDisabled и т.п. — пропускаем видео
            }
        }
    }

    /** Ошибки, при которых продолжать бессмысленно: неверный ключ или исчерпана квота. */
    private static boolean isFatal(YouTubeClient.ApiException e) {
        return e.reason.equals("quotaExceeded") || e.reason.equals("keyInvalid")
                || e.reason.equals("dailyLimitExceeded") || e.reason.equals("accessNotConfigured")
                || e.status == 400 && e.getMessage().toLowerCase().contains("api key");
    }
}
