# Jester

A floating, animated jester mascot for your desktop and phone. On the Mac it watches your
[Claude Code](https://claude.com/claude-code) sessions and reacts: thinking, typing, a raised hand when Claude needs
your OK, a backflip when it's done. On Android it floats over other apps with reminders, voice and sound effects.

- Vector jester rig: IK limbs, spring hat tails, juggling, backflips, pratfalls, idle tricks, particles
- Synthesised sound effects and babble (no audio files), spoken jester voice lines
- Pixel-art robot included as an alternative; make your own with the [bot pack format](docs/HUB_API.md#bot-packs-same-mascot-on-every-device)
- Works fully offline. An optional self-hosted [hub](docs/HUB_API.md) syncs reminders, chat and packs across devices

| Folder | What | Docs |
|---|---|---|
| [`mac/`](mac/) | macOS 13+ menu-bar-less floating app + Claude Code hook | [mac/README.md](mac/README.md) |
| [`android/`](android/) | Android floating overlay app | [android/README.md](android/README.md) |
| [`packs/`](packs/) | Bundled mascot packs (`jester`, `pixel`) | |
| [`docs/`](docs/) | Optional hub API contract | [docs/HUB_API.md](docs/HUB_API.md) |

## Quick start

**Mac** (needs Xcode command-line tools and Claude Code):
```bash
git clone https://github.com/tupesanket1999/jester && cd jester/mac && ./install.sh
```
Then add the hooks shown in [mac/README.md](mac/README.md) to `~/.claude/settings.json`.

**Android** (Android Studio or SDK + JDK 17):
```bash
cd android && ./gradlew :app:assembleDebug && adb install app/build/outputs/apk/debug/app-debug.apk
```
Grant "Display over other apps" when asked. Release signing: see [android/README.md](android/README.md).

## Contributing
Issues and pull requests welcome. Keep changes small, run the tests (`cd android && ./gradlew :app:testDebugUnitTest`)
and make sure the Mac app still compiles (`cd mac && ./install.sh` or the `swiftc` lines in it).

## License
[MIT](LICENSE)
