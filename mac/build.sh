#!/bin/bash
# Build Jester into <outdir>: <outdir>/bin/jester-hook and <outdir>/Jester.app (with bot packs). No install, no launchd.
# Used by install.sh and by the Homebrew formula.  usage: ./build.sh <outdir> [version]
set -euo pipefail
cd "$(dirname "$0")"
OUT="${1:?usage: build.sh <outdir> [version]}"; VERSION="${2:-0.1.0}"
PACKS="$PWD/../packs"
APP="$OUT/Jester.app"
mkdir -p "$OUT/bin" "$APP/Contents/MacOS" "$APP/Contents/Resources/bots"
echo "• compiling jester-hook"
swiftc -O -swift-version 5 app/jester-hook.swift -o "$OUT/bin/jester-hook"
echo "• compiling Jester.app"
swiftc -O -swift-version 5 app/main.swift app/Jester.swift app/JesterAudio.swift -o "$APP/Contents/MacOS/Jester" \
  -framework AppKit -framework Carbon -framework AVFoundation
cat > "$APP/Contents/Info.plist" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>io.github.tupesanket1999.jester</string>
  <key>CFBundleName</key><string>Jester</string>
  <key>CFBundleDisplayName</key><string>Jester</string>
  <key>CFBundleExecutable</key><string>Jester</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
</dict></plist>
PL
rm -rf "$APP/Contents/Resources/bots"/*
cp -R "$PACKS/jester" "$PACKS/pixel" "$APP/Contents/Resources/bots/"
codesign -s - --force --deep "$APP" >/dev/null 2>&1 || true
echo "✓ built $OUT/bin/jester-hook and $APP"
