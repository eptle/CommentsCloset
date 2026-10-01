#!/usr/bin/env bash
# Собирает dist/CommentsCloset-x86_64.AppImage (нужны JDK 21+ с jpackage и сеть для appimagetool).
set -euo pipefail
cd "$(dirname "$0")/.."

./mvnw -q -B package
rm -rf dist && mkdir -p dist/input
cp target/comments-closet.jar dist/input/

jpackage --type app-image --name CommentsCloset --app-version 1.0.0 \
  --input dist/input --main-jar comments-closet.jar --main-class com.commentscloset.Launcher \
  --icon packaging/icon.png --dest dist

APPDIR=dist/CommentsCloset.AppDir
mkdir -p "$APPDIR/usr"
cp -r dist/CommentsCloset/* "$APPDIR/usr/"
cp packaging/icon.png "$APPDIR/CommentsCloset.png"
cp packaging/icon.png "$APPDIR/.DirIcon"
cat > "$APPDIR/CommentsCloset.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=CommentsCloset
Exec=CommentsCloset
Icon=CommentsCloset
Categories=Network;
DESK
cat > "$APPDIR/AppRun" <<'RUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/CommentsCloset" "$@"
RUN
chmod +x "$APPDIR/AppRun"

TOOL=dist/appimagetool
curl -sL -o "$TOOL" https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
chmod +x "$TOOL"
ARCH=x86_64 "$TOOL" --appimage-extract-and-run "$APPDIR" dist/CommentsCloset-x86_64.AppImage
echo "Готово: dist/CommentsCloset-x86_64.AppImage"
