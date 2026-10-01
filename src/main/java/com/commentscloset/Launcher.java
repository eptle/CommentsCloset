package com.commentscloset;

/** Точка входа для fat jar / jpackage: класс не наследует Application, иначе JavaFX требует module path. */
public final class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
