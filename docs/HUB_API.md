# Optional hub API

Jester works on its own. A **hub** is an optional server you run yourself that syncs reminders, lists, chat and bot
packs across your devices. No hub is configured by default. This page is the contract a hub must implement; any
language or host works (a Cloudflare Worker, a small VPS, a home server).

Base URL: configured in each app (must be `https://`).
Auth: `Authorization: Bearer <device token>` on every `/api/*` call except `/api/act/*`.
All bodies are JSON. Errors: `{ "error": string }` with 400/401/403/404/422/500.
Clients work offline: they queue writes and retry later.

## Capture and lists
| Method | Path | Body → Response |
|---|---|---|
| POST | `/api/inbox` | `{ text, key? }` → `201 { task }`. `key` (≤80 chars `[A-Za-z0-9_.:-]`): idempotency; a retry with the same key within 7 days returns the original task. Suggested parse: "call mom tomorrow 6pm !", "in 2 min", "every monday 7am", `!` important, `!!` urgent |
| GET | `/api/tasks` | → `{ tasks: Task[] }`. `?dueWithin=24h` (1–168): only open tasks due before now+N hours, overdue included |
| GET | `/api/ringing` | → `{ ringing: [{ id, title, level, due }] }` reminders fired and not yet done or snoozed |
| POST | `/api/items/:id/done` | → `{ ok }` |
| POST | `/api/items/:id/snooze` | `{ minutes }` → `{ ok }` |
| POST | `/api/focus` | `{ minutes, label? }` → `201 { task }` (reminder "🎯 focus done: label" at now+minutes) |

`Task = { id, done, title, due?: "YYYY-MM-DD HH:mm" (floating local time), recur?: "every day|every week|every weekday|every month|every <weekday>", priority?: "urgent"|"important", doneDate?: "YYYY-MM-DD", file: "lists/<name>.md" }`

## Status and devices
| Method | Path | Body → Response |
|---|---|---|
| GET | `/api/status` | → `{ now, tz, lastTickAt, pendingOps, missed: [{id,title,due}], ai: { provider, configured, usedToday, capToday } }` |
| POST | `/api/devices/me` | `{ tz }` (IANA) → `{ ok, tz }` |
| GET | `/health` | no auth: `{ ok, lastTickAt, lastError?, pendingOps }` |

## Bot packs (same mascot on every device)
| Method | Path | Response |
|---|---|---|
| GET | `/api/bots` | → `{ bots: string[], current: string }` |
| GET | `/api/bots/:name` | → `{ name, manifest: <bot.json object>, parts: <parts.px text> }` |

Pack format: see [`../packs/README.md`](../packs/README.md) if present, or the bundled packs in `packs/`.
`bot.json` = `{ name, parts: "parts.px", scale, fps, bob, accent: [r,g,b], states: { <state>: Frame[] } }`,
`Frame = Array<string | { layer, dx?, dy? }>` (layers drawn in order). `parts.px`: `@palette` lines `<char> r g b`,
then `@layer <name> <x> <y>` followed by rows; `.` = transparent; `#` lines are comments. States: `idle thinking working
waiting done error remind sleep` (fall back to `idle`). Reference renderer: `mac/tools/preview.swift`.

## AI chat (optional)
| Method | Path | Body → Response |
|---|---|---|
| POST | `/api/chat` | `{ text, key? }` → `{ reply, actions: [{ kind: "added"\|"done"\|"remembered"\|"forgot", id?, title? }] }`. Without AI: `{ reply, actions: [], ai: false }`; plain capture still works via `/api/inbox` |
| GET | `/api/memory` | → `{ facts: [{ text, source, date }] }` |
| POST | `/api/memory` | `{ fact, source? }` → `201 { ok }` |
| POST | `/api/memory/forget` | `{ match }` → `{ removed: number }` |

## Mascot signals (Mac, optional)
`POST /api/signals` with `{ type, count?, ws?, minutes? }` (counts and state only, never session content).
Types: `claude_done claude_needs_ok claude_idle`. A hub may forward them as push nudges to your phone or watch;
reject unknown keys or types with 422.

## Push (optional)
A hub can push via [ntfy](https://ntfy.sh) to a topic you choose; the Android app subscribes to it.
Notification actions are signed one-tap URLs: `POST /api/act/<token>` (no device token needed).
Reminder pushes may carry a machine tag `tt:<id>@<YYYY-MM-DDTHH:mm>#<level>` in `tags` for dedupe and escalation.
