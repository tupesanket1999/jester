// jester-hook: Claude Code hook -> per-session state file for the Jester mascot.
// usage: jester-hook <EventName>   (hook JSON on stdin)
// Contract: never print to stdout (except the block decision for "jester:" notes, only when a hub is configured), always exit 0, finish in a few ms.
import Foundation
import Darwin

let env = ProcessInfo.processInfo.environment
if env["JESTER_CHILD"] != nil { exit(0) }  // the mascot's own summarizer runs

let fm = FileManager.default
let home = env["HOME"].flatMap { $0.isEmpty ? nil : $0 } ?? fm.homeDirectoryForCurrentUser.path
let base = home + "/.cache/jester"
let sessionsDir = base + "/sessions"
if fm.fileExists(atPath: base + "/disabled") { exit(0) }

let event = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "unknown"
let raw = FileHandle.standardInput.readDataToEndOfFile()
guard let input = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any],
      let sid = input["session_id"] as? String, !sid.isEmpty,
      sid.range(of: "^[A-Za-z0-9._-]+$", options: .regularExpression) != nil
else { exit(0) }

func clip(_ s: String?, _ n: Int) -> String {
  guard let s = s else { return "" }
  let one = s.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces)
  return one.count > n ? String(one.prefix(n - 1)) + "…" : one
}
func base(_ p: String?) -> String { (p as NSString?)?.lastPathComponent ?? "" }
let now = Date().timeIntervalSince1970

try? fm.createDirectory(atPath: sessionsDir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
// Optional hub: when ~/.config/jester/hub.json exists, "jester: buy milk 6pm" typed in any Claude session is a note
// for Jester. Queue it in the outbox (the mascot app sends it to the hub) and BLOCK the prompt so it never reaches Claude.
if event == "UserPromptSubmit", fm.fileExists(atPath: home + "/.config/jester/hub.json"),
   let prompt = input["prompt"] as? String,
   let r = prompt.range(of: "^\\s*jester:\\s*", options: [.regularExpression, .caseInsensitive]) {
  let text = String(prompt[r.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
  if !text.isEmpty {
    let outbox = base + "/outbox"
    try? fm.createDirectory(atPath: outbox, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    let item: [String: Any] = ["kind": "inbox", "text": String(text.prefix(500)), "ts": now]
    if let data = try? JSONSerialization.data(withJSONObject: item) {
      fm.createFile(atPath: outbox + "/\(Int(now * 1000))-\(getpid()).json", contents: data, attributes: [.posixPermissions: 0o600])
    }
    let reply: [String: Any] = ["decision": "block", "reason": "📝 Sent to Jester (not sent to Claude): \(text.prefix(80))"]
    if let out = try? JSONSerialization.data(withJSONObject: reply) { FileHandle.standardOutput.write(out) }
  }
  exit(0)
}

let path = sessionsDir + "/" + sid + ".json"
var s: [String: Any] = (try? JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path)))) as? [String: Any] ?? [:]

// Host app: walk parent processes to the first GUI .app (Ghostty, VS Code, Claude.app, ...).
func parentPid(_ pid: pid_t) -> pid_t {
  var info = kinfo_proc(); var size = MemoryLayout<kinfo_proc>.stride
  var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, pid]
  if sysctl(&mib, 4, &info, &size, nil, 0) != 0 || size == 0 { return 0 }
  return info.kp_eproc.e_ppid
}
func exePath(_ pid: pid_t) -> String {
  var buf = [CChar](repeating: 0, count: 4096)
  return proc_pidpath(pid, &buf, 4096) > 0 ? String(cString: buf) : ""
}
if s["host_pid"] == nil {
  var pid = getppid(); var hops = 0
  while pid > 1 && hops < 30 {
    let p = exePath(pid)
    if let r = p.range(of: ".app/") {
      s["host_pid"] = Int(pid)
      s["host_app"] = String(p[..<r.lowerBound]) + ".app"
      // outermost app owns helpers (VS Code "Code Helper.app" lives inside it); keep walking for that pid
      var q = parentPid(pid)
      while q > 1, exePath(q).hasPrefix(s["host_app"] as! String) { s["host_pid"] = Int(q); q = parentPid(q) }
      break
    }
    pid = parentPid(pid); hops += 1
  }
  if s["host_pid"] == nil { s["host_pid"] = 0; s["host_app"] = env["TERM_PROGRAM"] ?? "" }
}

let cwd = input["cwd"] as? String ?? (s["cwd"] as? String ?? "")
s["session_id"] = sid
s["cwd"] = cwd
s["project"] = base(cwd)
s["event"] = event
s["ts"] = now
if let t = input["transcript_path"] as? String { s["transcript"] = t }

let tool = input["tool_name"] as? String ?? ""
let ti = input["tool_input"] as? [String: Any] ?? [:]
func toolDetail() -> String {
  switch tool {
  case "Bash": return "running " + clip((ti["command"] as? String)?.split(separator: " ").prefix(3).joined(separator: " "), 28)
  case "Edit", "Write", "MultiEdit", "NotebookEdit": return "editing " + clip(base(ti["file_path"] as? String ?? ti["notebook_path"] as? String), 26)
  case "Read": return "reading " + clip(base(ti["file_path"] as? String), 26)
  case "Grep", "Glob": return "searching"
  case "WebFetch", "WebSearch": return "browsing the web"
  case "Agent", "Task": return "delegating to a sub-agent"
  case "Skill": return "loading skill " + clip(ti["skill"] as? String, 20)
  case "AskUserQuestion": return "has a question for you"
  default:
    if tool.hasPrefix("mcp__") { return "using " + clip(tool.split(separator: "_").dropFirst(2).first.map(String.init), 20) }
    return clip(tool, 28)
  }
}

switch event {
case "SessionStart":
  s["state"] = "idle"; s["detail"] = "session started"; s["started"] = now
  s.removeValue(forKey: "ended")
case "UserPromptSubmit":
  s["state"] = "thinking"; s["detail"] = "thinking…"; s["turn_started"] = now
  let p = clip(input["prompt"] as? String, 300)
  var prompts = s["prompts"] as? [String] ?? []
  if !p.isEmpty { prompts.append(p) }
  s["prompts"] = Array(prompts.suffix(3))
  s["last_prompt"] = clip(p, 120)
  s.removeValue(forKey: "waiting_since")
case "PreToolUse":
  s["state"] = tool == "AskUserQuestion" ? "waiting" : "working"; s["tool"] = tool; s["detail"] = toolDetail()
  if tool == "AskUserQuestion" { s["waiting_since"] = now } else { s.removeValue(forKey: "waiting_since") }
case "PostToolUse":
  s["state"] = "working"; s.removeValue(forKey: "waiting_since")
case "PostToolUseFailure":
  s["state"] = "error"; s["detail"] = clip(tool, 20) + " failed"
case "Notification":
  let kind = input["notification_type"] as? String ?? ""
  if kind == "idle_prompt" {
    s["idle_nudge"] = now
  } else {
    s["state"] = "waiting"; s["waiting_since"] = s["waiting_since"] ?? now
    s["detail"] = kind == "permission_prompt" ? "needs your OK" : clip(input["message"] as? String, 40)
  }
case "Stop":
  s["state"] = "done"; s["detail"] = "done ✓"; s["done_at"] = now
  s["last_reply"] = clip(input["last_assistant_message"] as? String, 800)
  s["needs_summary"] = true
  s.removeValue(forKey: "waiting_since")
case "SessionEnd":
  let prev = s["state"] as? String ?? ""
  s["unfinished_end"] = (prev == "thinking" || prev == "working" || prev == "waiting")
  s["state"] = "ended"; s["ended"] = now
default: break
}

if let data = try? JSONSerialization.data(withJSONObject: s, options: [.sortedKeys]) {
  let tmp = path + ".tmp\(getpid())"
  if fm.createFile(atPath: tmp, contents: data, attributes: [.posixPermissions: 0o600]) {
    rename(tmp, path)
  }
}
exit(0)
