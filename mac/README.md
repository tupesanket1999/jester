# Jester for macOS

A floating, animated court jester that watches your [Claude Code](https://claude.com/claude-code) sessions (any terminal, IDE or
the desktop app) and taps you on the shoulder when something needs you. It runs entirely on your Mac and needs no account,
server or Accessibility permission.

- **done**: a prompt finished while you were in another app
- **idle**: a finished session has been sitting unattended for 3 min
- **needs your OK**: a session is blocked on a permission prompt for more than 20 s
- **resume**: you come back to a project after a break and get what you were doing and the next step (Haiku summary)
- **parked**: sessions that closed mid-task
- **break**: after 50 min of continuous work

Click the bubble (or the bot) to jump to that session's window. With [AeroSpace](https://github.com/nikitabobko/AeroSpace)
it focuses the exact window and workspace, otherwise it activates the app. Right-click the bot for the session list,
parked work, bot picker, clear, hide and quit.

While Claude works, the bot only changes pose (thinking, typing, hand raised, thumbs up, X-eyes). It speaks only when
something is done or needs you. Set `"show_activity": true` in the config for a chattier bubble.

## Build and install

Requires macOS 13+, the Xcode command-line tools (`swiftc`) and Claude Code. Run from a clone of this repo:

```bash
cd mac
./install.sh
```

`install.sh` compiles `jester-hook` and `Jester.app` (bundle id `io.github.tupesanket1999.jester`) into
`~/.local/share/jester`, copies the bot packs from [`../packs`](../packs), links `jester` and `jester-hook` into
`~/.local/bin` (make sure that is on your `PATH`), and starts a LaunchAgent. It never touches `~/.claude/settings.json`.

## Hooks

Add the hooks to `~/.claude/settings.json`, **appending** to any existing arrays:

| Event | Matcher | Command |
|---|---|---|
| SessionStart, UserPromptSubmit, Notification, Stop, SessionEnd | (none) | `~/.local/bin/jester-hook <Event>` |
| PreToolUse, PostToolUse, PostToolUseFailure | `*` | `~/.local/bin/jester-hook <Event>` |

Each entry looks like `{"type": "command", "command": "<your-home>/.local/bin/jester-hook Stop", "timeout": 3}`.
Use the absolute path of your home directory (hooks do not expand `~` reliably). Then run `jester demo` to watch a fake session walk through every state.

## Use

```
jester on | off | status | bot <name> | bots | forget | demo | voice off|away|always|test
```

- `off` stops the app and turns the hooks into no-ops; `on` brings both back.
- `demo` walks a fake session through every state.
- `⌃⌥J` hides or shows the bot. `⌃Space` dismisses the current bubble. Drag the bot anywhere; the position is remembered.
  Both shortcuts can be changed (`"dismiss_hotkey"`, or `""` to disable).

Config: `~/.config/jester/config.json`

```json
{ "bot": "jester", "show_activity": false, "idle_remind_after": 180, "voice": "away", "sfx": "on" }
```

## How it works

```
Claude Code hook --stdin JSON--> jester-hook --> ~/.cache/jester/sessions/<id>.json
                                                      | (polled 4x/s)
                                                      v
                                      Jester.app: rules -> pose + bubble
                                                      | on Stop
                                                      v
                         claude -p --model haiku  -->  ~/.cache/jester/projects/<project>.json
```

- **`jester-hook`** (Swift, about 10 ms): never blocks or alters a tool call and always exits 0. It records state, a short
  detail, the project, and the GUI app hosting the session (found by walking parent processes). Session ids are validated
  before they are used as file names; files are `0600` in a `0700` directory.
- **`Jester.app`** (Swift/AppKit, LaunchAgent `io.github.tupesanket1999.jester`): a borderless panel on every Space,
  click-through except over the bot and the bubble. The frontmost app comes from NSWorkspace.
- **Summaries** run in the app, not the hook: `claude -p --model haiku --no-session-persistence --setting-sources "" --tools ""`
  with `JESTER_CHILD=1`, so the summarizer's own hooks are skipped. Input is the last 3 prompts (300 chars each) and
  the last reply (800 chars), at most once per 2 min per session, and only through your local `claude` login.
  Everything stays in `~/.cache/jester`.

## The jester (default bot)

A vector court jester drawn by a procedural rig (`app/Jester.swift`): two-bone IK arms and legs, spring-physics hat tails
with bells, squash and stretch, and poses that cross-fade between states.

| State | What he does |
|---|---|
| idle | breathes, shifts weight, eyes follow your cursor; every 8-18 s a trick: hat jingle, shuffle dance, card flourish, quick juggle, peek, yawn |
| thinking | hand on chin, gazing into a glowing crystal ball, thought bubbles |
| working | 3-ball cascade (faster for Bash), quill on a scroll for Edit/Write, reads a scroll for Read/Grep/Web |
| waiting | hops and waves his scepter, red "!" |
| done | crouch, backflip, confetti landing, ta-da, bow, then celebrates |
| error | slips, pratfall, sits dazed with orbiting stars |
| remind / sleep | friendly wave / dozes on his scepter with floating z's |

Rendering is a cutout rig: every rigid part is rasterised once into a sprite and the GPU composites the layers, so it
runs at 120 Hz while moving fast (60 Hz calm, 20 Hz asleep) for about 10% of one core.

**Sound.** Bells, boing, whoosh, slide-whistle, thud, ta-da chord and a giggle are synthesised in code
(`app/JesterAudio.swift`) and fired by the choreography; new bubble lines get a short "babble". The optional spoken voice
uses the built-in macOS voices ("Huzzah! my-project hath finished, my liege!"); nothing leaves the Mac. The audio engine
only runs while something plays.

Config keys: `"fps"` (max frame rate, default 120), `"sfx"`: `on` | `away` | `off`, `"sfx_volume"` (0-1, default 0.35),
`"babble"` (true/false), `"voice"`: `off` | `away` | `always`, `"voice_name"`, `"debug_fps"` (log the real frame rate).
Quiet hours (23:00-07:00) silence everything. `"bot": "pixel"` switches to the pixel-art robot.

Preview every state offline: `swiftc -O tools/jester-sheet/main.swift app/Jester.swift -o /tmp/jsheet && /tmp/jsheet sheet.png`
(optional second arg: row indexes, e.g. `6,7`).

## Make your own bot

A bot pack is a folder in `~/.config/jester/bots/<name>/` with a `bot.json`. It hot-reloads on save. The bundled packs live in
[`../packs`](../packs) (`jester`, `pixel`).

```json
{
  "name": "my-bot", "parts": "parts.px", "scale": 4, "fps": 6, "bob": 2, "accent": [230, 57, 70],
  "states": {
    "idle":    [["body", "eyes_open"], ["body", "eyes_blink"]],
    "working": [["body", {"layer": "arm", "dy": -1}], ["body", "arm"]],
    "done":    [{"png": "done1.png"}, {"png": "done2.png"}]
  }
}
```

- States: `idle thinking working waiting done error remind sleep`. Missing states fall back to similar ones, and `idle` is required.
- A frame is either a list of pixel layers (with optional `dx`/`dy` offsets) or a PNG.
- `parts.px` is plain text: a `@palette` of `<char> r g b` lines, then `@layer <name> <x> <y>` blocks of rows (`.` = transparent).
  See [`../packs/pixel/parts.px`](../packs/pixel/parts.px).
- Preview a pack as a PNG sheet: `swift tools/preview.swift ../packs/<name> out.png`.
- Switch with `jester bot <name>` or the right-click menu.

## Optional: hub

Jester also works as a personal reminder and memory sidekick if you run a small HTTP service (a "hub") that stores your
reminders, notes and memory. Everything below is off until you configure one; the Claude Code companion needs none of it.

```bash
jester hub setup https://your-hub.example.com   # saves the URL, prompts for a device token (kept in the Keychain, service jester-hub)
jester hub status
jester add "call mom tomorrow 6pm"               # queue a note; the app sends it within ~10 s
```

With a hub configured, the app pulls ringing reminders into the bubble (click to mark done), sends counts-only status
signals (done / needs OK / idle) when you are away from the keyboard, and enables the Ask box (`⌃⌥T`, or set
`"ask_hotkey"`, e.g. `"cmd+shift+space"`; clicking the bot also opens it). Ask replies come from your local `claude`
(Haiku) with your open items and known facts as context, or from the hub's own chat endpoint (`"ask_ai": "hub"`).
Typing `jester: buy milk` in a Claude Code prompt queues the note and keeps it out of the Claude conversation.
The API the hub must implement is described in [`../docs/HUB_API.md`](../docs/HUB_API.md).

## Uninstall

```bash
jester off
launchctl bootout "gui/$(id -u)/io.github.tupesanket1999.jester" 2>/dev/null
rm ~/Library/LaunchAgents/io.github.tupesanket1999.jester.plist ~/.local/bin/jester-hook ~/.local/bin/jester
rm -rf ~/.local/share/jester ~/.cache/jester ~/.config/jester
# and remove the jester-hook entries from ~/.claude/settings.json
```
