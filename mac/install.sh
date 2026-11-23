#!/bin/bash
# Build + install the Jester mascot. Touches Claude Code settings only if you say yes (via `jester hooks install`).
set -euo pipefail
cd "$(dirname "$0")"
DEST="$HOME/.local/share/jester"
APP="$DEST/Jester.app"
BIN="$HOME/.local/bin"
LABEL="io.github.tupesanket1999.jester"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
mkdir -p "$BIN" "$HOME/.cache/jester"
chmod 700 "$HOME/.cache/jester"
./build.sh "$DEST"

ln -sf "$DEST/bin/jester-hook" "$BIN/jester-hook"
ln -sf "$PWD/jester" "$BIN/jester"
chmod +x jester

cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>$APP/Contents/MacOS/Jester</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>ProcessType</key><string>Interactive</string>
  <key>StandardErrorPath</key><string>$HOME/.cache/jester/mascot.err</string>
</dict></plist>
PL
echo "• restarting agent"
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
rm -f "$HOME/.cache/jester/disabled"
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "✓ installed: $APP, $BIN/jester-hook, $BIN/jester"
if [ -t 0 ]; then
  read -r -p "Add Jester's hooks to your Claude Code settings now? [Y/n] " yn
  case "${yn:-y}" in [Yy]*) "$BIN/jester" hooks install ;; *) echo "Later: jester hooks install" ;; esac
else
  echo "Next: jester hooks install   (adds the Claude Code hooks; see README.md, section Hooks)"
fi
echo "Try it: jester demo"
