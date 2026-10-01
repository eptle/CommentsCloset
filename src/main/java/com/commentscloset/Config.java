package com.commentscloset;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Настройки пользователя: API-ключ и ссылки на каналы. Хранятся в config.json. */
public class Config {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String apiKey = "";
    public List<String> channels = new ArrayList<>();

    private static Path file() {
        return AppPaths.dataDir().resolve("config.json");
    }

    public static Config load() {
        try {
            Path f = file();
            if (Files.exists(f)) return MAPPER.readValue(f.toFile(), Config.class);
        } catch (Exception ignored) {
            // повреждённый конфиг — начинаем с пустого
        }
        return new Config();
    }

    public void save() {
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file().toFile(), this);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сохранить настройки", e);
        }
    }
}
