package com.commentscloset;

import java.nio.file.Files;
import java.nio.file.Path;

/** Каталог данных приложения: %APPDATA%\CommentsCloset на Windows, ~/.config/CommentsCloset на Linux. */
public final class AppPaths {
    private AppPaths() {}

    public static Path dataDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        Path base;
        if (os.contains("win") && System.getenv("APPDATA") != null) {
            base = Path.of(System.getenv("APPDATA"));
        } else if (System.getenv("XDG_CONFIG_HOME") != null && !System.getenv("XDG_CONFIG_HOME").isBlank()) {
            base = Path.of(System.getenv("XDG_CONFIG_HOME"));
        } else {
            base = Path.of(System.getProperty("user.home"), ".config");
        }
        Path dir = base.resolve("CommentsCloset");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось создать каталог " + dir, e);
        }
        return dir;
    }
}
