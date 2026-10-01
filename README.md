# CommentsCloset

Десктопное приложение (Windows и Linux): собирает комментарии под видео выбранных YouTube-каналов
через YouTube Data API v3, хранит их в локальной SQLite и показывает в окне или небольшом виджете.

## Использование
1. Получите API-ключ: [инструкция](https://developers.google.com/youtube/v3/getting-started) (YouTube Data API v3).
2. Вставьте ключ в поле «API-ключ» и ссылки на каналы (по одной в строке; `@handle`, `/channel/UC…`, `/user/…`).
3. Нажмите «Обновить комментарии». Повторные запуски докачивают только новые комментарии.
4. «Виджет» — маленькое окно поверх всех, раз в 10 секунд показывает случайный комментарий (перетаскивается, ✕ закрывает).

Настройки и база лежат в `%APPDATA%\CommentsCloset` (Windows) или `~/.config/CommentsCloset` (Linux).
Ключ хранится в `config.json` открытым текстом.

Квота API — 10 000 единиц в сутки; приложение берёт 50 последних видео канала, запрос комментариев стоит 1 единицу.

## Сборка
- Запуск из исходников: `./mvnw package && java -jar target/comments-closet.jar`
- Linux AppImage: `./scripts/package-linux.sh` → `dist/CommentsCloset-x86_64.AppImage`
- Windows exe: `scripts\package-windows.ps1` (нужен WiX Toolset 3.x) → `dist\CommentsCloset-1.0.0.exe`
- Оба варианта собирает GitHub Actions (`.github/workflows/build.yml`, вручную или по тегу `v*`).
  `jpackage` не умеет кросс-компиляцию, поэтому каждая ОС собирается на своей.
