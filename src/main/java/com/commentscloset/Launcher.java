package com.commentscloset;

/** Точка входа для fat jar / jpackage: класс не наследует Application, иначе JavaFX требует module path. */
public final class Launcher {
    public static void main(String[] args) {
        if (args.length >= 1 && args[0].equals("--generate-seed")) {
            SeedGenerator.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length == 2 && args[0].equals("--export-seed")) {
            try (Db db = new Db()) {
                db.exportTo(java.nio.file.Path.of(args[1]));
                System.out.println("БД сохранена: " + args[1] + " (комментариев: " + db.count() + ")");
            }
            return;
        }
        App.main(args);
    }
}
