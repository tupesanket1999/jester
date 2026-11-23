// Jester.app: a floating animated mascot for Claude Code. Reads ~/.cache/jester/sessions/*.json written by jester-hook,
// animates a bot pack, and shows reminders in a speech bubble.
import AppKit
import Carbon.HIToolbox
import AVFoundation

// MARK: paths
let fm = FileManager.default
let HOME = ProcessInfo.processInfo.environment["HOME"].flatMap { $0.isEmpty ? nil : $0 } ?? fm.homeDirectoryForCurrentUser.path
let CACHE = HOME + "/.cache/jester"
let SESSIONS = CACHE + "/sessions"
let PROJECTS = CACHE + "/projects"
let CONFIG_DIR = HOME + "/.config/jester"
let USER_BOTS = CONFIG_DIR + "/bots"
let LOG = CACHE + "/mascot.log"

func log(_ msg: String) {
  let line = "\(ISO8601DateFormatter().string(from: Date())) \(msg)\n"
  if let h = FileHandle(forWritingAtPath: LOG) { h.seekToEndOfFile(); h.write(line.data(using: .utf8)!); h.closeFile() }
  else { fm.createFile(atPath: LOG, contents: line.data(using: .utf8), attributes: [.posixPermissions: 0o600]) }
}
func readJSON(_ path: String) -> [String: Any]? {
  guard let d = fm.contents(atPath: path) else { return nil }
  return (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
}
func writeJSON(_ path: String, _ obj: [String: Any]) {
  guard let d = try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys, .prettyPrinted]) else { return }
  let tmp = path + ".tmp"
  fm.createFile(atPath: tmp, contents: d, attributes: [.posixPermissions: 0o600]); rename(tmp, path)
}
func mtime(_ path: String) -> Double {
  ((try? fm.attributesOfItem(atPath: path))?[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
}

// MARK: bot pack
struct Pack {
  var name = ""
  var scale: CGFloat = 4
  var fps: Double = 6
  var bob: CGFloat = 2
  var accent = NSColor(srgbRed: 230/255, green: 57/255, blue: 70/255, alpha: 1)
  var size = NSSize(width: 24, height: 28)
  var frames: [String: [CGImage]] = [:]
  var stamp: Double = 0
  var dir = ""
  var rig: String?          // "jester": procedural vector rig drawn every display frame instead of frames

  func anim(_ state: String) -> [CGImage] {
    if let f = frames[state], !f.isEmpty { return f }
    let fallback = ["remind": "waiting", "sleep": "idle", "error": "idle", "done": "idle", "thinking": "working", "working": "idle", "waiting": "idle"]
    if let fb = fallback[state], let f = frames[fb], !f.isEmpty { return f }
    return frames["idle"] ?? []
  }

  static func locate(_ name: String) -> String? {
    let user = USER_BOTS + "/" + name
    if fm.fileExists(atPath: user + "/bot.json") { return user }
    if let res = Bundle.main.resourcePath, fm.fileExists(atPath: res + "/bots/" + name + "/bot.json") { return res + "/bots/" + name }
    return nil
  }
  static func stampOf(_ dir: String) -> Double {
    ((try? fm.contentsOfDirectory(atPath: dir)) ?? []).map { mtime(dir + "/" + $0) }.max() ?? 0
  }

  static func load(_ dir: String) -> Pack? {
    guard let m = readJSON(dir + "/bot.json") else { log("pack: bad bot.json in \(dir)"); return nil }
    var p = Pack(); p.dir = dir; p.stamp = stampOf(dir)
    p.name = m["name"] as? String ?? (dir as NSString).lastPathComponent
    p.rig = m["rig"] as? String
    p.scale = CGFloat(m["scale"] as? Double ?? 4); p.fps = m["fps"] as? Double ?? 6; p.bob = CGFloat(m["bob"] as? Double ?? 2)
    if let a = m["accent"] as? [Double], a.count == 3 { p.accent = NSColor(srgbRed: a[0]/255, green: a[1]/255, blue: a[2]/255, alpha: 1) }

    // pixel layers (optional)
    var palette: [Character: (UInt8, UInt8, UInt8)] = [:]
    var layers: [String: (Int, Int, [String])] = [:]
    var w = 0, h = 0
    if let partsName = m["parts"] as? String, let text = try? String(contentsOfFile: dir + "/" + partsName, encoding: .utf8) {
      var mode = "", cur = "", cx = 0, cy = 0; var rows: [String] = []
      func flush() { if !cur.isEmpty { layers[cur] = (cx, cy, rows); w = max(w, cx + (rows.map { $0.count }.max() ?? 0)); h = max(h, cy + rows.count) }; cur = ""; rows = [] }
      for raw in text.components(separatedBy: "\n") {
        let line = raw.trimmingCharacters(in: CharacterSet(charactersIn: "\r"))
        if line.hasPrefix("#") || line.trimmingCharacters(in: .whitespaces).isEmpty { continue }
        if line.hasPrefix("@palette") { flush(); mode = "p"; continue }
        if line.hasPrefix("@layer") {
          flush(); let f = line.split(separator: " ")
          guard f.count >= 4, let x = Int(f[2]), let y = Int(f[3]) else { continue }
          cur = String(f[1]); cx = x; cy = y; mode = "l"; continue
        }
        if mode == "p" {
          let f = line.split(separator: " ")
          if f.count == 4, let ch = f[0].first, let r = UInt8(f[1]), let g = UInt8(f[2]), let b = UInt8(f[3]) { palette[ch] = (r, g, b) }
        } else if mode == "l" { rows.append(line) }
      }
      flush()
    }
    if let sz = m["size"] as? [Int], sz.count == 2 { w = sz[0]; h = sz[1] }
    var pngSize = NSSize.zero

    func pixelImage(_ items: [Any]) -> CGImage? {
      guard w > 0, h > 0 else { return nil }
      var buf = [UInt8](repeating: 0, count: w * h * 4)
      for item in items {
        var name = "", dx = 0, dy = 0
        if let s = item as? String { name = s } else if let d = item as? [String: Any] { name = d["layer"] as? String ?? ""; dx = d["dx"] as? Int ?? 0; dy = d["dy"] as? Int ?? 0 }
        guard let (lx, ly, rows) = layers[name] else { continue }
        for (r, row) in rows.enumerated() { for (c, ch) in row.enumerated() {
          guard ch != ".", let col = palette[ch] else { continue }
          let x = lx + c + dx, y = ly + r + dy
          guard x >= 0, x < w, y >= 0, y < h else { continue }
          let i = (y * w + x) * 4; buf[i] = col.0; buf[i+1] = col.1; buf[i+2] = col.2; buf[i+3] = 255
        } }
      }
      let cs = CGColorSpace(name: CGColorSpace.sRGB)!
      guard let prov = CGDataProvider(data: Data(buf) as CFData) else { return nil }
      return CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4, space: cs,
                     bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue), provider: prov,
                     decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }

    for (state, list) in (m["states"] as? [String: Any]) ?? [:] {
      var imgs: [CGImage] = []
      for frame in (list as? [Any]) ?? [] {
        if let items = frame as? [Any], let img = pixelImage(items) { imgs.append(img) }
        else if let d = frame as? [String: Any], let png = d["png"] as? String,
                let ns = NSImage(contentsOfFile: dir + "/" + png),
                let cg = ns.cgImage(forProposedRect: nil, context: nil, hints: nil) {
          imgs.append(cg); pngSize = NSSize(width: cg.width, height: cg.height)
        } else if let png = frame as? String, png.hasSuffix(".png"),
                  let ns = NSImage(contentsOfFile: dir + "/" + png),
                  let cg = ns.cgImage(forProposedRect: nil, context: nil, hints: nil) {
          imgs.append(cg); pngSize = NSSize(width: cg.width, height: cg.height)
        }
      }
      p.frames[state] = imgs
    }
    p.size = w > 0 ? NSSize(width: w, height: h) : pngSize
    if p.rig == nil, p.frames["idle"]?.isEmpty ?? true { log("pack \(p.name): no idle frames"); return nil }
    log("pack loaded: \(p.name) from \(dir) states=\(p.frames.keys.sorted())")
    return p
  }
}

// MARK: views
final class BotView: NSView {
  var image: CGImage? { didSet { if image !== oldValue { needsDisplay = true } } }
  var rig: JesterRig? { didSet { if rig == nil { rigLayers?.root.removeFromSuperlayer(); rigLayers = nil } } }
  private var rigLayers: JesterLayers?
  /// Rig frames skip AppKit's display pass: sprites are built once, then only layer transforms change (GPU composited).
  func renderRig() {
    guard let rig, let host = layer else { return }
    let scale = window?.backingScaleFactor ?? 2
    let key = "\(rig.zoom)|\(scale)|\(bounds.size)|\(rig.accent)"
    if rigLayers?.builtFor != key {
      rigLayers?.root.removeFromSuperlayer()
      let L = JesterLayers(); L.build(rig, size: bounds.size, backing: scale); L.builtFor = key
      host.addSublayer(L.root); rigLayers = L
    }
    rigLayers?.update(rig)
  }
  var lastGlow: CGFloat = -1
  var glow: NSColor = .clear
  var glowStrength: CGFloat = 0.6
  var dim = false
  var sleeping = false
  var onDrag: ((NSPoint) -> Void)?
  var onDragEnd: (() -> Void)?
  var onClick: (() -> Void)?
  var menuProvider: (() -> NSMenu)?
  private var downAt = NSPoint.zero
  private var dragged = false

  override var isFlipped: Bool { false }
  /// Pre-rendered (frame + glow + alpha) images: the shadow blur is expensive, so each combination is drawn once.
  private var cache: [String: CGImage] = [:]
  private func rendered(_ img: CGImage, size: NSSize) -> CGImage? {
    let bucket = Int((dim ? glowStrength * 0.5 : glowStrength) * 10)
    let key = "\(ObjectIdentifier(img).hashValue)|\(bucket)|\(dim)|\(sleeping)|\(Int(size.width))x\(Int(size.height))"
    if let c = cache[key] { return c }
    let scale = window?.backingScaleFactor ?? 2
    let w = Int(size.width * scale), h = Int(size.height * scale)
    guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                              space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
    ctx.scaleBy(x: scale, y: scale)
    ctx.interpolationQuality = .none
    let r = NSRect(origin: .zero, size: size).insetBy(dx: 10, dy: 10)
    ctx.setFillColor(NSColor.black.withAlphaComponent(dim ? 0.12 : 0.22).cgColor)
    ctx.fillEllipse(in: NSRect(x: r.midX - r.width * 0.28, y: 2, width: r.width * 0.56, height: 7))
    ctx.setShadow(offset: .zero, blur: dim ? 9 : 16, color: glow.withAlphaComponent(CGFloat(bucket) / 10).cgColor)
    ctx.setAlpha(sleeping ? 0.6 : (dim ? 0.92 : 1))
    ctx.draw(img, in: r)
    guard let out = ctx.makeImage() else { return nil }
    if cache.count > 400 { cache.removeAll() }
    cache[key] = out
    return out
  }
  override func draw(_ dirtyRect: NSRect) {
    if rig != nil { return }   // drawn by renderRig()
    guard let img = image, let ctx = NSGraphicsContext.current?.cgContext, let out = rendered(img, size: bounds.size) else { return }
    ctx.interpolationQuality = .none
    ctx.draw(out, in: bounds)
  }
  override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
  override func mouseDown(with e: NSEvent) { downAt = NSEvent.mouseLocation; dragged = false }
  override func mouseDragged(with e: NSEvent) {
    let p = NSEvent.mouseLocation
    if abs(p.x - downAt.x) + abs(p.y - downAt.y) > 3 { dragged = true }
    onDrag?(NSPoint(x: p.x - downAt.x, y: p.y - downAt.y)); downAt = p
  }
  override func mouseUp(with e: NSEvent) { if dragged { onDragEnd?() } else { onClick?() } }
  override func rightMouseDown(with e: NSEvent) {
    if let m = menuProvider?() { NSMenu.popUpContextMenu(m, with: e, for: self) }
  }
}

final class BubbleView: NSVisualEffectView {
  let label = NSTextField(wrappingLabelWithString: "")
  var full = ""
  var shown = 0
  var onClick: (() -> Void)?
  var onDismiss: (() -> Void)?
  var accent: NSColor = .systemRed { didSet { layer?.borderColor = accent.withAlphaComponent(0.85).cgColor } }

  override init(frame: NSRect) {
    super.init(frame: frame)
    material = .hudWindow; blendingMode = .behindWindow; state = .active
    wantsLayer = true; layer?.cornerRadius = 14; layer?.borderWidth = 1.5; layer?.masksToBounds = true
    label.font = NSFont.monospacedSystemFont(ofSize: 12.5, weight: .medium)
    label.textColor = NSColor(srgbRed: 1, green: 236/255, blue: 232/255, alpha: 1)
    label.maximumNumberOfLines = 4
    label.lineBreakMode = .byWordWrapping
    addSubview(label)
    alphaValue = 0
  }
  required init?(coder: NSCoder) { fatalError() }
  override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
  override func hitTest(_ point: NSPoint) -> NSView? { frame.contains(point) && alphaValue > 0.5 ? self : nil }
  override func mouseDown(with e: NSEvent) {}
  override func mouseUp(with e: NSEvent) { onClick?() }
  override func rightMouseDown(with e: NSEvent) { onDismiss?() }   // right-click = dismiss without acting

  func set(_ text: String) {
    if text == full { return }
    if !full.isEmpty && text.hasPrefix(full) { full = text; return }   // streaming: keep typing from where we are
    full = text; shown = 0; label.stringValue = ""
  }
  /// typewriter step; returns true when text changed
  func tick() {
    guard shown < full.count else { return }
    shown = min(full.count, shown + 2)
    label.stringValue = String(full.prefix(shown))
  }
  func layoutFor(maxWidth: CGFloat) -> NSSize {
    label.preferredMaxLayoutWidth = maxWidth - 24
    let probe = NSTextField(wrappingLabelWithString: full); probe.font = label.font; probe.maximumNumberOfLines = 4
    probe.preferredMaxLayoutWidth = maxWidth - 24
    let fit = probe.fittingSize
    let size = NSSize(width: min(maxWidth, ceil(fit.width) + 24), height: ceil(fit.height) + 18)
    label.frame = NSRect(x: 12, y: 9, width: size.width - 24, height: size.height - 18)
    return size
  }
}

// MARK: model
struct Session {
  let id: String; let project: String; let cwd: String; let state: String; let detail: String
  let ts: Double; let hostPid: Int; let hostApp: String
  let waitingSince: Double?; let doneAt: Double?; let raw: [String: Any]
  init(_ d: [String: Any]) {
    raw = d; id = d["session_id"] as? String ?? ""; project = d["project"] as? String ?? "?"; cwd = d["cwd"] as? String ?? ""
    state = d["state"] as? String ?? "idle"; detail = d["detail"] as? String ?? ""; ts = d["ts"] as? Double ?? 0
    hostPid = d["host_pid"] as? Int ?? 0; hostApp = d["host_app"] as? String ?? ""
    waitingSince = d["waiting_since"] as? Double; doneAt = d["done_at"] as? Double
  }
  var hostName: String { ((hostApp as NSString).lastPathComponent as NSString).deletingPathExtension }
}

struct Reminder {
  let key: String; let text: String; let sessionId: String?; let hostPid: Int; let sticky: Bool; let created: Double
}

// MARK: AeroSpace (window id / workspace / title per app pid)
struct AeroWin { let id: Int; let pid: Int; let workspace: String; let title: String }
let AEROSPACE = ["/opt/homebrew/bin/aerospace", "/usr/local/bin/aerospace"].first { fm.isExecutableFile(atPath: $0) }
func aerospace(_ args: [String]) -> String? {
  guard let bin = AEROSPACE else { return nil }
  let p = Process(); p.executableURL = URL(fileURLWithPath: bin); p.arguments = args
  let out = Pipe(); p.standardOutput = out; p.standardError = FileHandle.nullDevice
  do { try p.run() } catch { return nil }
  let d = out.fileHandleForReading.readDataToEndOfFile(); p.waitUntilExit()
  return p.terminationStatus == 0 ? String(data: d, encoding: .utf8) : nil
}
func aeroWindows() -> [AeroWin] {
  guard let txt = aerospace(["list-windows", "--all", "--format", "%{window-id}|%{app-pid}|%{workspace}|%{window-title}"]) else { return [] }
  return txt.split(separator: "\n").compactMap { line in
    let f = line.split(separator: "|", maxSplits: 3, omittingEmptySubsequences: false).map(String.init)
    guard f.count == 4, let id = Int(f[0]), let pid = Int(f[1]) else { return nil }
    return AeroWin(id: id, pid: pid, workspace: f[2], title: f[3])
  }
}
/// Claude Code puts a spinner glyph in front of the session title; drop it.
func cleanTitle(_ t: String) -> String {
  var x = t.trimmingCharacters(in: .whitespaces)
  if let first = x.unicodeScalars.first, !CharacterSet.alphanumerics.contains(first) { x = String(x.dropFirst()).trimmingCharacters(in: .whitespaces) }
  return x.count > 42 ? String(x.prefix(41)) + "…" : x
}

// MARK: hub client (optional). Config: ~/.config/jester/hub.json {"url": ...}; see docs/HUB_API.md.
// Device token in the macOS Keychain (service "jester-hub"), read through /usr/bin/security.
// With no hub configured every hub function is a no-op.
let OUTBOX = CACHE + "/outbox"
let hubQ = DispatchQueue(label: "hub")
var hubTokenCache: String?

func hubURL() -> String? { (readJSON(CONFIG_DIR + "/hub.json")?["url"] as? String)?.trimmingCharacters(in: CharacterSet(charactersIn: "/ ")) }
func hubToken() -> String? {
  if let t = hubTokenCache { return t }
  let p = Process(); p.executableURL = URL(fileURLWithPath: "/usr/bin/security")
  p.arguments = ["find-generic-password", "-s", "jester-hub", "-w"]
  let out = Pipe(); p.standardOutput = out; p.standardError = FileHandle.nullDevice
  guard (try? p.run()) != nil else { return nil }
  let d = out.fileHandleForReading.readDataToEndOfFile(); p.waitUntilExit()
  guard p.terminationStatus == 0, let t = String(data: d, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return nil }
  hubTokenCache = t; return t
}
/// Synchronous HTTP for the hub queue (never call on the main thread). Returns (status, body).
func hubCall(_ method: String, _ path: String, _ body: [String: Any]? = nil) -> (Int, Data?) {
  guard let base = hubURL(), let tok = hubToken(), let url = URL(string: base + path) else { return (0, nil) }
  var req = URLRequest(url: url, timeoutInterval: 10)
  req.httpMethod = method
  req.setValue("Bearer \(tok)", forHTTPHeaderField: "authorization")
  if let b = body { req.httpBody = try? JSONSerialization.data(withJSONObject: b); req.setValue("application/json", forHTTPHeaderField: "content-type") }
  let sem = DispatchSemaphore(value: 0); var res: (Int, Data?) = (0, nil)
  URLSession.shared.dataTask(with: req) { d, r, _ in res = ((r as? HTTPURLResponse)?.statusCode ?? 0, d); sem.signal() }.resume()
  _ = sem.wait(timeout: .now() + 12)
  if res.0 == 401 { hubTokenCache = nil }
  return res
}
/// Queue something for the hub (works offline; the app drains the outbox). Signals are counts only.
func enqueue(_ item: [String: Any]) {
  try? fm.createDirectory(atPath: OUTBOX, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
  guard let d = try? JSONSerialization.data(withJSONObject: item) else { return }
  fm.createFile(atPath: OUTBOX + "/\(Int(Date().timeIntervalSince1970 * 1000))-\(Int.random(in: 0..<1_000_000)).json", contents: d, attributes: [.posixPermissions: 0o600])
}
extension ISO8601DateFormatter {
  static let withFractions: ISO8601DateFormatter = { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return f }()
}
func userIdleSeconds() -> Double {
  CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: CGEventType(rawValue: ~0)!)
}

/// "cmd+b", "ctrl+alt+space", "cmd+shift+k" -> (virtual key code, Carbon modifiers)
func parseHotkey(_ spec: String) -> (UInt32, UInt32)? {
  let parts = spec.lowercased().split(separator: "+").map { $0.trimmingCharacters(in: .whitespaces) }
  guard let keyName = parts.last, !keyName.isEmpty else { return nil }
  var mods: UInt32 = 0
  for m in parts.dropLast() {
    switch m {
    case "cmd", "command", "⌘": mods |= UInt32(cmdKey)
    case "ctrl", "control", "⌃": mods |= UInt32(controlKey)
    case "alt", "opt", "option", "⌥": mods |= UInt32(optionKey)
    case "shift", "⇧": mods |= UInt32(shiftKey)
    default: return nil
    }
  }
  let letters: [String: Int] = ["a": kVK_ANSI_A, "b": kVK_ANSI_B, "c": kVK_ANSI_C, "d": kVK_ANSI_D, "e": kVK_ANSI_E, "f": kVK_ANSI_F, "g": kVK_ANSI_G, "h": kVK_ANSI_H, "i": kVK_ANSI_I, "j": kVK_ANSI_J, "k": kVK_ANSI_K, "l": kVK_ANSI_L, "m": kVK_ANSI_M, "n": kVK_ANSI_N, "o": kVK_ANSI_O, "p": kVK_ANSI_P, "q": kVK_ANSI_Q, "r": kVK_ANSI_R, "s": kVK_ANSI_S, "t": kVK_ANSI_T, "u": kVK_ANSI_U, "v": kVK_ANSI_V, "w": kVK_ANSI_W, "x": kVK_ANSI_X, "y": kVK_ANSI_Y, "z": kVK_ANSI_Z, "space": kVK_Space, "/": kVK_ANSI_Slash, ".": kVK_ANSI_Period]
  guard mods != 0, let code = letters[keyName] else { return nil }
  return (UInt32(code), mods)
}

// MARK: warm Claude session (stream-json): no process start per question, replies stream in
final class ClaudeSession {
  static let system = """
  You are Jester, a cheeky court-jester sidekick for reminders, checklists and memory.
  Witty and playful (a quick joke or pun when it fits) but brief: one or two short sentences.
  Each message gives you the current time, the user's open items and known facts, then what the user says.
  Reply with ONLY compact JSON, "reply" first: {"reply": string, "add": [string], "done": [string], "remember": [string], "forget": [string]}
  - add: new todos/reminders, keep the user's own time words in the text, e.g. "call mom tomorrow 6pm" ("!" important, "!!" urgent).
  - done: ids from Open items only. remember: lasting facts about the user. forget: text to match facts to remove. Omit empty arrays.
  - Never store secrets, credentials or source code; just answer.
  """
  private var proc: Process?
  private var stdin: FileHandle?
  private var spare: (Process, FileHandle, Pipe)?   // pre-warmed replacement
  private var lastContext = ""
  private var warming = false
  private var queued: (() -> Void)?

  private var buffer = Data()
  private var turns = 0
  private var current: (delta: (String) -> Void, done: (String?) -> Void)?
  private var accumulated = ""
  private var timeout: DispatchWorkItem?
  private let q = DispatchQueue(label: "claude-session")
  let bin: String
  init(bin: String) { self.bin = bin }

  private func spawn() -> (Process, FileHandle, Pipe)? {
    let p = Process(); p.executableURL = URL(fileURLWithPath: bin)
    p.arguments = ["-p", "--model", "haiku", "--no-session-persistence", "--setting-sources", "", "--tools", "",
                   "--input-format", "stream-json", "--output-format", "stream-json", "--include-partial-messages", "--verbose",
                   "--system-prompt", ClaudeSession.system]
    var e = ProcessInfo.processInfo.environment; e["JESTER_CHILD"] = "1"; p.environment = e
    p.currentDirectoryURL = URL(fileURLWithPath: CACHE)
    let inp = Pipe(), out = Pipe()
    p.standardInput = inp; p.standardOutput = out; p.standardError = FileHandle.nullDevice
    do { try p.run(); return (p, inp.fileHandleForWriting, out) } catch { log("claude session: spawn failed \(error)"); return nil }
  }
  /// make a spawned process the live one
  private func adopt(_ s: (Process, FileHandle, Pipe)) {
    let (p, inp, out) = s
    out.fileHandleForReading.readabilityHandler = { [weak self, weak p] h in
      let d = h.availableData
      self?.q.async { if self?.proc === p { self?.ingest(d) } }
    }
    p.terminationHandler = { [weak self, weak p] _ in self?.q.async { if self?.proc === p { self?.died() } } }
    proc = p; stdin = inp; turns = 0; buffer = Data(); lastContext = ""
    log("claude session: live")
    // silent warm-up so the first real question doesn't pay for loading the prompt
    let msg: [String: Any] = ["type": "user", "message": ["role": "user", "content": [["type": "text", "text": "Warm-up ping. Reply {\"reply\":\"ok\"}"]]]]
    if var line = try? JSONSerialization.data(withJSONObject: msg) {
      line.append(0x0A); warming = true
      current = ({ _ in }, { [weak self] _ in self?.q.async { self?.warming = false; let next = self?.queued; self?.queued = nil; next?() } })
      try? inp.write(contentsOf: line)
    }
  }
  private func start() { if let s = spare { spare = nil; adopt(s) } else if let s = spawn() { adopt(s) } }
  private func died() {
    proc = nil; stdin = nil; buffer = Data()
    if let c = current { current = nil; timeout?.cancel(); c.done(nil) }
  }
  func stop() { q.async { self.proc?.terminate() } }
  /// Start the process ahead of time so the first question is fast too.
  func warm() { q.async { if self.proc == nil { self.start() } } }

  /// delta: growing reply text as it streams; done: final JSON text (nil = failed -> caller falls back)
  /// head: "Now: …"; context: open items + facts (sent only when this live session hasn't seen it); tail: what the user said
  func ask(head: String, context: String, tail: String, delta: @escaping (String) -> Void, done: @escaping (String?) -> Void) {
    q.async {
      if self.warming { self.queued = { self.ask(head: head, context: context, tail: tail, delta: delta, done: done) }; return }
      if self.current != nil { done(nil); return }                       // one at a time
      if self.turns >= 10 {                                               // keep context small, swap in the warm spare
        let old = self.proc; self.proc = nil; self.stdin = nil; old?.terminate()
      }
      if self.turns >= 7 && self.spare == nil { self.spare = self.spawn() }  // warm the replacement early
      if self.proc == nil { self.start() }
      guard let stdin = self.stdin else { done(nil); return }
      // decided after any swap above, so a fresh session always gets the context
      let message = head + (context == self.lastContext ? "(Open items and facts unchanged since my last message.)\n" : context + "\n") + tail
      self.lastContext = context
      self.current = (delta, done); self.accumulated = ""; self.turns += 1
      let msg: [String: Any] = ["type": "user", "message": ["role": "user", "content": [["type": "text", "text": message]]]]
      guard var line = try? JSONSerialization.data(withJSONObject: msg) else { self.current = nil; done(nil); return }
      line.append(0x0A)
      let t = DispatchWorkItem { [weak self] in self?.q.async { log("claude session: timeout"); self?.proc?.terminate() } }
      self.timeout = t; DispatchQueue.global().asyncAfter(deadline: .now() + 25, execute: t)
      do { try stdin.write(contentsOf: line) } catch { self.proc?.terminate() }
    }
  }
  private func ingest(_ d: Data) {
    if d.isEmpty { return }
    buffer.append(d)
    while let nl = buffer.firstIndex(of: 0x0A) {
      let lineData = buffer[buffer.startIndex..<nl]; buffer.removeSubrange(buffer.startIndex...nl)
      guard let ev = (try? JSONSerialization.jsonObject(with: lineData)) as? [String: Any], let type = ev["type"] as? String else { continue }
      if type == "stream_event", let e = ev["event"] as? [String: Any], (e["type"] as? String) == "content_block_delta",
         let dl = e["delta"] as? [String: Any], let txt = dl["text"] as? String {
        accumulated += txt
        if let r = ClaudeSession.partialReply(accumulated) { let c = current; DispatchQueue.main.async { c?.delta(r) } }
      } else if type == "result" {
        timeout?.cancel()
        let final = (ev["result"] as? String) ?? accumulated
        let c = current; current = nil
        DispatchQueue.main.async { c?.done((ev["is_error"] as? Bool) == true ? nil : final) }
      }
    }
  }
  /// The "reply" value from a partial JSON stream like {"reply": "Why did the cl  -> "Why did the cl"
  static func partialReply(_ s: String) -> String? {
    guard let r = s.range(of: "\"reply\"\\s*:\\s*\"", options: .regularExpression) else { return nil }
    var out = "", esc = false
    for ch in s[r.upperBound...] {
      if esc { out.append(ch == "n" ? "\n" : ch); esc = false; continue }
      if ch == "\\" { esc = true; continue }
      if ch == "\"" { break }
      out.append(ch)
    }
    return out
  }
}

// MARK: Ask box (text input). A panel that can take keyboard focus without a Dock icon.
final class AskPanel: NSPanel {
  override var canBecomeKey: Bool { true }
  override func cancelOperation(_ sender: Any?) { orderOut(nil) }   // Esc closes
}

// MARK: controller
// MARK: voice output. config.json "voice": "off" | "away" (default: speak only when you are not at the keyboard and the
// keyboard is idle) | "always" (also reads Ask replies). "voice_name": optional system voice name. Silent 23:00-07:00.
// Uses the built-in macOS voices: nothing leaves the Mac.
final class Voice {
  private let synth = AVSpeechSynthesizer()
  private var lastSpoken: [String: Double] = [:]
  private lazy var voice: AVSpeechSynthesisVoice? = Voice.pickVoice()
  var mode: String { (readJSON(CONFIG_DIR + "/config.json")?["voice"] as? String) ?? "away" }
  static func pickVoice() -> AVSpeechSynthesisVoice? {
    let want = ((readJSON(CONFIG_DIR + "/config.json")?["voice_name"] as? String) ?? "").lowercased()
    let all = AVSpeechSynthesisVoice.speechVoices().filter { $0.language.hasPrefix("en") }
    if !want.isEmpty, let v = all.first(where: { $0.name.lowercased() == want || $0.identifier.lowercased() == want }) { return v }
    func rank(_ v: AVSpeechSynthesisVoice) -> Int {
      var r = v.quality == .premium ? 30 : v.quality == .enhanced ? 20 : 0
      r += v.language == "en-IN" ? 5 : (v.language == "en-US" || v.language == "en-GB") ? 3 : 0
      if v.voiceTraits.contains(.isNoveltyVoice) { r -= 50 }
      return r
    }
    return all.max { rank($0) < rank($1) } ?? AVSpeechSynthesisVoice(language: "en-US")
  }
  static func quiet(_ d: Date = Date()) -> Bool { let h = Calendar.current.component(.hour, from: d); return h >= 23 || h < 7 }
  /// Letters, digits, spaces and light punctuation only (no emoji / symbols read aloud).
  static func plain(_ s: String) -> String {
    let keep = CharacterSet.alphanumerics.union(.whitespaces).union(CharacterSet(charactersIn: ",.:;'!?-()"))
    return String(String.UnicodeScalarView(s.unicodeScalars.filter { keep.contains($0) }))
      .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
  }
  func speak(_ text: String, key: String, minGap: Double = 20) {
    let now = Date().timeIntervalSince1970
    if let last = lastSpoken[key], now - last < minGap { return }
    lastSpoken[key] = now
    let u = AVSpeechUtterance(string: Voice.plain(text))
    u.voice = voice
    u.rate = min(AVSpeechUtteranceMaximumSpeechRate, AVSpeechUtteranceDefaultSpeechRate * 1.08)
    u.pitchMultiplier = 1.3          // a little higher and quicker: court-jester energy
    u.preUtteranceDelay = 0.35       // let the bell jingle land first
    if synth.isSpeaking { synth.stopSpeaking(at: .word) }   // newest wins
    synth.speak(u)
  }
  func stop() { synth.stopSpeaking(at: .immediate) }
  var isSpeaking: Bool { synth.isSpeaking }
  /// pick a line from a pool, never the same one twice in a row
  private var lastLine: [String: String] = [:]
  func pick(_ pool: String, _ lines: [String]) -> String {
    let choices = lines.count > 1 ? lines.filter { $0 != lastLine[pool] } : lines
    let l = choices.randomElement() ?? ""; lastLine[pool] = l; return l
  }
}

final class Mascot: NSObject, NSApplicationDelegate, NSTextFieldDelegate {
  var panel: NSPanel!
  let bot = BotView()
  let bubble = BubbleView(frame: .zero)
  var pack = Pack()
  var packName = "jester"
  var showActivity = false  // config "show_activity": bubble for thinking/working/error too
  var idleAfter = 180.0     // config "idle_remind_after" seconds
  var idleIssued: Set<String> = []
  var bubblePid = 0
  var askPanel: AskPanel?
  let askField = NSTextField()
  var appBeforeAsk: NSRunningApplication?
  var askTyped = false
  var openItems: [[String: Any]] = []     // cached from the hub (refreshed ~every minute)
  var factsCache: [String] = []
  var lastFactsPull = 0.0
  let claudeQ = DispatchQueue(label: "claude-ask")
  var claudeSession: ClaudeSession?
  var hotKeyRef2: EventHotKeyRef?
  var hotKeyRef3: EventHotKeyRef?
  var hotKeyRef4: EventHotKeyRef?
  var shownStateKey = ""        // session+event currently shown as a status line
  var dismissedHub: Set<String> = []   // ringing hub reminders hidden on this Mac (other devices still nag)
  var dismissedStateKey = ""    // status line the user dismissed (hidden until that session changes)
  var aeroCache: [AeroWin] = []   // refreshed off the main thread (AeroSpace queries us via AX; never block on it)
  let aeroQ = DispatchQueue(label: "aerospace")
  var frameIdx = 0
  var lastFrameAt = 0.0
  var rigLink: CADisplayLink?
  var rigTimer: Timer?
  var rigLastOrigin = NSPoint.zero
  var displayTool = ""
  var rigTicks = 0, rigTickT0 = 0.0
  var rigFPSCap = 120.0     // config "fps": upper bound for the jester's frame rate
  var t0 = Date().timeIntervalSince1970
  var sessions: [String: Session] = [:]
  var prevState: [String: String] = [:]
  var reminders: [Reminder] = []
  var transientText: String?
  var transientUntil = 0.0
  var displayState = "sleep"
  var hidden = false
  let voice = Voice()
  let audio = JesterAudio()
  var lastEventAt = 0.0
  var streakStart = 0.0
  var lastBreakNudge = 0.0
  var lastWaitNudge: [String: Double] = [:]
  var summarizing: Set<String> = []
  var frontPid: pid_t = 0
  var claudeBin: String?
  var hotKeyRef: EventHotKeyRef?
  let W: CGFloat = 380, H: CGFloat = 260

  func applicationDidFinishLaunching(_ n: Notification) {
    try? fm.createDirectory(atPath: SESSIONS, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    try? fm.createDirectory(atPath: PROJECTS, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    try? fm.createDirectory(atPath: USER_BOTS, withIntermediateDirectories: true)
    claudeBin = [HOME + "/.local/bin/claude", "/opt/homebrew/bin/claude", "/usr/local/bin/claude"].first { fm.isExecutableFile(atPath: $0) }
    loadConfig(); reloadPack(force: true)

    panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: W, height: H), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
    panel.isOpaque = false; panel.backgroundColor = .clear; panel.hasShadow = false
    panel.level = .statusBar
    panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]
    panel.isMovable = false; panel.hidesOnDeactivate = false; panel.ignoresMouseEvents = true
    panel.title = "Jester"
    let root = NSView(frame: NSRect(x: 0, y: 0, width: W, height: H)); root.wantsLayer = true
    panel.contentView = root
    root.addSubview(bubble); root.addSubview(bot)
    bubble.onClick = { [weak self] in self?.bubbleClicked() }
    bubble.onDismiss = { [weak self] in self?.dismissTop() }
    bot.onClick = { [weak self] in self?.botClicked() }
    bot.onDrag = { [weak self] d in guard let self else { return }; var o = self.panel.frame.origin; o.x += d.x; o.y += d.y; self.panel.setFrameOrigin(o) }
    bot.onDragEnd = { [weak self] in self?.savePosition() }
    bot.menuProvider = { [weak self] in self?.buildMenu() ?? NSMenu() }
    layoutBot()
    placeWindow()
    panel.orderFrontRegardless()

    NSWorkspace.shared.notificationCenter.addObserver(self, selector: #selector(frontChanged(_:)), name: NSWorkspace.didActivateApplicationNotification, object: nil)
    frontPid = NSWorkspace.shared.frontmostApplication?.processIdentifier ?? 0
    registerHotKey()

    Timer.scheduledTimer(withTimeInterval: 1.0 / 20, repeats: true) { [weak self] _ in self?.animate() }
    Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in self?.poll() }
    Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in self?.reloadPack(force: false); self?.loadConfig() }
    Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in self?.updateMouseThrough() }
    Timer.scheduledTimer(withTimeInterval: 5.0, repeats: true) { [weak self] _ in self?.refreshAero() }
    refreshAero()
    Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in self?.hubSync() }
    log("started pid=\(getpid()) claude=\(claudeBin ?? "none")")
    if let bin = claudeBin, ((readJSON(CONFIG_DIR + "/config.json")?["ask_ai"] as? String) ?? "claude") == "claude" {
      claudeSession = ClaudeSession(bin: bin)
      DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in self?.claudeSession?.warm() }
    }
  }

  // config: ~/.config/jester/config.json {"bot": "...", "x": .., "y": ..}
  func loadConfig() {
    let c = readJSON(CONFIG_DIR + "/config.json") ?? [:]
    if let b = c["bot"] as? String, b != packName { packName = b; reloadPack(force: true) }
    showActivity = c["show_activity"] as? Bool ?? false
    idleAfter = c["idle_remind_after"] as? Double ?? 180
    rigFPSCap = c["fps"] as? Double ?? 120
  }
  func savePosition() {
    var c = readJSON(CONFIG_DIR + "/config.json") ?? [:]
    c["x"] = Double(panel.frame.origin.x); c["y"] = Double(panel.frame.origin.y)
    try? fm.createDirectory(atPath: CONFIG_DIR, withIntermediateDirectories: true)
    writeJSON(CONFIG_DIR + "/config.json", c)
  }
  func placeWindow() {
    let c = readJSON(CONFIG_DIR + "/config.json") ?? [:]
    if let x = c["x"] as? Double, let y = c["y"] as? Double,
       NSScreen.screens.contains(where: { $0.frame.intersects(NSRect(x: x, y: y, width: W, height: H)) }) {
      panel.setFrameOrigin(NSPoint(x: x, y: y)); return
    }
    let vf = (NSScreen.main ?? NSScreen.screens[0]).visibleFrame
    panel.setFrameOrigin(NSPoint(x: vf.maxX - W - 8, y: vf.minY + 8))
  }
  func reloadPack(force: Bool) {
    guard let dir = Pack.locate(packName) ?? Pack.locate("jester") ?? Pack.locate("pixel") else { return }
    if !force && dir == pack.dir && Pack.stampOf(dir) == pack.stamp { return }
    if let p = Pack.load(dir) { pack = p; frameIdx = 0; layoutBot(); setupRig() }
  }
  /// Rig packs redraw on the display link (120 Hz on ProMotion, 144 Hz on the monitor); sprite packs keep the 20 Hz timer.
  func setupRig() {
    guard pack.rig == "jester" else {
      bot.rig = nil; rigLink?.invalidate(); rigLink = nil; rigTimer?.invalidate(); rigTimer = nil; bot.alphaValue = 1; return
    }
    let r = bot.rig ?? JesterRig()
    r.zoom = pack.scale; r.accent = pack.accent.cgColor
    r.onSound = { [weak self] name in self?.sfx(name) }
    bot.rig = r
    guard rigLink == nil && rigTimer == nil else { return }
    if #available(macOS 14.0, *) {
      let link = bot.displayLink(target: self, selector: #selector(rigTick))
      link.add(to: .main, forMode: .common); rigLink = link
    } else {
      rigTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 60, repeats: true) { [weak self] _ in self?.rigTick() }
    }
  }
  /// config "sfx": on (default) | away | off, "sfx_volume": 0...1 (default 0.35); silent in quiet hours and when hidden
  func sfxAllowed() -> Bool {
    let c = readJSON(CONFIG_DIR + "/config.json") ?? [:]
    let m = c["sfx"] as? String ?? "on"
    if m == "off" || hidden || Voice.quiet() { return false }
    audio.volume = Float(c["sfx_volume"] as? Double ?? 0.35)
    return m == "on" || userIdleSeconds() > 60
  }
  func sfx(_ name: String) {
    guard sfxAllowed() else { return }
    audio.play(name, gain: name == "tinkle" ? 0.6 : 1, minGap: name == "tinkle" ? 0.7 : 0.12)
  }
  @objc func rigTick() {
    guard let rig = bot.rig, panel != nil else { return }
    if hidden || panel.alphaValue == 0 { return }
    let o = panel.frame.origin
    let delta = rigLastOrigin == .zero ? CGPoint.zero : CGPoint(x: o.x - rigLastOrigin.x, y: o.y - rigLastOrigin.y)
    rigLastOrigin = o
    let m = NSEvent.mouseLocation
    let local = CGPoint(x: m.x - o.x - bot.frame.minX, y: m.y - o.y - bot.frame.minY)
    rig.request(state: displayState, tool: displayTool)
    if #available(macOS 14.0, *), let link = rigLink {
      let cap = Float(max(15, min(144, rigFPSCap)))
      let want: Float = displayState == "sleep" && !rig.wantsHighFPS ? 20 : (rig.wantsHighFPS ? 120 : 60)
      let pref = min(cap, want)
      let r = CAFrameRateRange(minimum: min(pref, 15), maximum: pref, preferred: pref)
      if link.preferredFrameRateRange != r { link.preferredFrameRateRange = r }
    }
    rigTicks += 1
    if CACurrentMediaTime() - rigTickT0 > 10 {
      if (readJSON(CONFIG_DIR + "/config.json")?["debug_fps"] as? Bool) == true {
      log(String(format: "jester: %.0f fps state=%@ high=%d", Double(rigTicks) / (CACurrentMediaTime() - rigTickT0), displayState, rig.wantsHighFPS ? 1 : 0))
      }
      rigTicks = 0; rigTickT0 = CACurrentMediaTime()
    }
    rig.step(time: CACurrentMediaTime(), windowDelta: delta, mouseLocal: local, viewSize: bot.bounds.size)
    let a: CGFloat = bot.sleeping ? 0.8 : (bot.dim ? 0.93 : 1)
    if bot.alphaValue != a { bot.alphaValue = a }
    bot.renderRig()
  }
  func layoutBot() {
    let bw = pack.size.width * pack.scale + 20, bh = pack.size.height * pack.scale + 20
    bot.frame = NSRect(x: W - bw, y: 0, width: bw, height: bh)
    bot.glow = pack.accent; bubble.accent = pack.accent
  }

  // MARK: input from hooks
  func poll() {
    let now = Date().timeIntervalSince1970
    if fm.fileExists(atPath: CACHE + "/disabled") { if !panel.isVisible || panel.alphaValue > 0 { panel.alphaValue = 0 }; return }
    else if !hidden && panel.alphaValue == 0 { panel.alphaValue = 1 }

    var fresh: [String: Session] = [:]
    for f in (try? fm.contentsOfDirectory(atPath: SESSIONS)) ?? [] where f.hasSuffix(".json") {
      let p = SESSIONS + "/" + f
      guard let d = readJSON(p) else { continue }
      let s = Session(d)
      if s.id.isEmpty { continue }
      if now - s.ts > 7 * 86400 { try? fm.removeItem(atPath: p); continue }
      fresh[s.id] = s
    }
    for (id, s) in fresh {
      let old = sessions[id]
      if old == nil || old!.ts != s.ts { onEvent(s, previous: old, now: now) }
    }
    sessions = fresh
    rules(now: now)
  }

  func onEvent(_ s: Session, previous: Session?, now: Double) {
    let wasState = prevState[s.id]
    prevState[s.id] = s.state
    let first = previous == nil
    if first && now - s.ts > 5 { return }  // old file at app start: no fanfare
    // activity streak (for break nudges) and resume detection
    if lastEventAt > 0 && now - lastEventAt > 600 { resumeReminder(for: s) }
    if lastEventAt == 0 || now - lastEventAt > 600 { streakStart = now }
    lastEventAt = now

    if s.state == "idle" && s.raw["event"] as? String == "SessionStart" { resumeReminder(for: s) }
    if s.state == "thinking" || s.state == "working" {
      // user is engaged with this session again: drop its stale reminders
      reminders.removeAll { $0.sessionId == s.id && ($0.key.hasPrefix("done") || $0.key.hasPrefix("idle")) }
    }
    if s.state == "done" && wasState != "done" {
      if away { signal("claude_done", s) }
      if !userIsIn(s) {
        addReminder(Reminder(key: "done:\(s.id)", text: "✓ done: \(where_(s))", sessionId: s.id, hostPid: s.hostPid, sticky: true, created: now))
      }
      if s.raw["needs_summary"] as? Bool == true { summarize(s) }
    }
    if s.state == "ended" {
      if s.raw["unfinished_end"] as? Bool == true { markParked(project: s.project, cwd: s.cwd, why: "session closed mid-task") }
      reminders.removeAll { $0.sessionId == s.id && !$0.key.hasPrefix("parked") }
    }
  }

  /// You are "away" when idle 5+ min: only then are status signals sent to the hub (if one is configured).
  var away: Bool { userIdleSeconds() > 300 }
  func workspaceNumber(_ s: Session) -> Int? {
    guard s.hostPid > 0, let w = aeroCache.first(where: { $0.pid == s.hostPid }) else { return nil }
    return Int(w.workspace)
  }
  /// Counts-only status signal for the optional hub.
  func signal(_ type: String, _ s: Session? = nil, minutes: Int? = nil) {
    guard hubURL() != nil else { return }
    var sig: [String: Any] = ["type": type]
    if let s, let ws = workspaceNumber(s) { sig["ws"] = ws }
    if let m = minutes { sig["minutes"] = m }
    enqueue(["kind": "signal", "signal": sig])
  }

  /// Every 10 s: drain the outbox to the hub, and pull reminders that are ringing.
  var lastRingingPull = 0.0
  func hubSync() {
    guard hubURL() != nil else { return }
    let pullRinging = Date().timeIntervalSince1970 - lastRingingPull > 50
    if pullRinging { lastRingingPull = Date().timeIntervalSince1970 }
    hubQ.async { [weak self] in
      let files = ((try? fm.contentsOfDirectory(atPath: OUTBOX)) ?? []).filter { $0.hasSuffix(".json") }.sorted()
      for f in files {
        let path = OUTBOX + "/" + f
        guard let item = readJSON(path) else { try? fm.removeItem(atPath: path); continue }
        let (code, _): (Int, Data?)
        // idempotency key = outbox file name: a retry after a lost response never duplicates
        let key = String(f.dropLast(5)).replacingOccurrences(of: "[^A-Za-z0-9_.:-]", with: "-", options: .regularExpression)
        if item["kind"] as? String == "inbox" { (code, _) = hubCall("POST", "/api/inbox", ["text": item["text"] ?? "", "key": "mac-" + key]) }
        else if item["kind"] as? String == "done" { (code, _) = hubCall("POST", "/api/items/\(item["id"] ?? "")/done") }
        else { (code, _) = hubCall("POST", "/api/signals", item["signal"] as? [String: Any] ?? [:]) }
        if (200..<300).contains(code) { try? fm.removeItem(atPath: path) }
        else if code >= 400 && code < 500 && code != 401 && code != 429 {
          try? fm.createDirectory(atPath: OUTBOX + "/failed", withIntermediateDirectories: true)
          try? fm.moveItem(atPath: path, toPath: OUTBOX + "/failed/" + f); log("hub rejected \(f): \(code)")
        } else { break } // offline or auth problem: keep order, retry next round
      }
      guard pullRinging else { return }
      // cache open items (and facts every 5 min) so the local Claude can answer without waiting on the hub
      if case (200, let d?) = hubCall("GET", "/api/tasks"), let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any],
         let ts = j["tasks"] as? [[String: Any]] {
        let open = ts.filter { ($0["done"] as? Bool) != true }.prefix(40).map { t -> [String: Any] in
          ["id": t["id"] ?? "", "title": t["title"] ?? "", "due": t["due"] ?? ""] }
        DispatchQueue.main.async { self?.openItems = Array(open) }
      }
      if Date().timeIntervalSince1970 - (self?.lastFactsPull ?? 0) > 300,
         case (200, let d?) = hubCall("GET", "/api/memory"), let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any],
         let fs = j["facts"] as? [[String: Any]] {
        let texts = fs.compactMap { $0["text"] as? String }.suffix(30)
        DispatchQueue.main.async { self?.factsCache = Array(texts); self?.lastFactsPull = Date().timeIntervalSince1970 }
      }
      let (code, data) = hubCall("GET", "/api/ringing")
      guard code == 200, let d = data, let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any],
            let ring = j["ringing"] as? [[String: Any]] else { return }
      DispatchQueue.main.async { self?.showRinging(ring) }
    }
  }
  func showRinging(_ ring: [[String: Any]]) {
    let now = Date().timeIntervalSince1970
    let ids = Set(ring.compactMap { $0["id"] as? String })
    reminders.removeAll { $0.key.hasPrefix("hub:") && !ids.contains(String($0.key.dropFirst(4))) }
    for r in ring {
      guard let id = r["id"] as? String, let title = r["title"] as? String else { continue }
      if reminders.contains(where: { $0.key == "hub:\(id)" }) || dismissedHub.contains(id) { continue }
      addReminder(Reminder(key: "hub:\(id)", text: "⏰ \(title)", sessionId: nil, hostPid: 0, sticky: true, created: now))
    }
  }

  func refreshAero() {
    guard AEROSPACE != nil else { return }
    aeroQ.async { let w = aeroWindows(); DispatchQueue.main.async { self.aeroCache = w } }
  }
  func where_(_ s: Session) -> String {
    if s.hostPid > 0, let w = aeroCache.first(where: { $0.pid == s.hostPid }) {
      let t = cleanTitle(w.title)
      return "ws\(w.workspace) · " + (t.isEmpty || t == "Claude Code" ? s.project : t)
    }
    return s.project + (s.hostName.isEmpty ? "" : " (\(s.hostName))")
  }
  func jump(pid: Int) {
    guard pid > 0 else { return }
    aeroQ.async {
      let t0 = Date()
      if let w = aeroWindows().first(where: { $0.pid == pid }) {
        if aerospace(["focus", "--window-id", String(w.id)]) != nil {
          // pointer is still on the mascot; with focus-follows-mouse (AutoRaise) the window under it would steal
          // focus back. Park the pointer in the target window, like AeroSpace's own on-focus-changed recipe.
          let moved = aerospace(["move-mouse", "window-force-center"]) != nil
          log("jump: aerospace focus window \(w.id) ws\(w.workspace) ok, mouse moved=\(moved), \(Int(Date().timeIntervalSince(t0) * 1000))ms")
          return
        }
        log("jump: aerospace focus window \(w.id) FAILED, falling back")
      } else { log("jump: no aerospace window for pid \(pid) (aerospace=\(AEROSPACE ?? "missing"))") }
      DispatchQueue.main.async {
        let ok = NSRunningApplication(processIdentifier: pid_t(pid))?.activate() ?? false
        log("jump: activate pid \(pid) -> \(ok)")
      }
    }
  }

  func userIsIn(_ s: Session) -> Bool {
    guard s.hostPid > 0 else { return false }
    return Int(frontPid) == s.hostPid
  }

  func rules(now: Double) {
    // `jester voice test` drops this file; speak once in the configured voice, whatever the mode
    if fm.fileExists(atPath: CACHE + "/voice-test") {
      try? fm.removeItem(atPath: CACHE + "/voice-test")
      voice.speak("Huzzah! Your jester is here. I shall shout when your prompts finish. Lovingly.", key: "test", minGap: 0)
    }
    // waiting for permission > 20 s and you are elsewhere -> nudge (repeat every 2 min)
    for s in sessions.values where s.state == "waiting" {
      guard let since = s.waitingSince, now - since > 20 else { continue }
      let last = lastWaitNudge[s.id] ?? 0
      if !userIsIn(s) && now - last > 120 {
        lastWaitNudge[s.id] = now
        if away { signal("claude_needs_ok", s) }
        addReminder(Reminder(key: "wait:\(s.id)", text: "⏳ needs your OK: \(where_(s))", sessionId: s.id, hostPid: s.hostPid, sticky: true, created: now))
      }
    }
    // waiting cleared -> drop its reminder
    reminders.removeAll { r in
      guard r.key.hasPrefix("wait:"), let id = r.sessionId else { return false }
      return sessions[id]?.state != "waiting"
    }
    // finished session left sitting idle while you are elsewhere -> one nudge per finished turn
    for s in sessions.values where s.state == "done" {
      guard let d = s.doneAt, now - d > idleAfter else { continue }
      let key = "idle:\(s.id):\(Int(d))"
      if idleIssued.contains(key) || userIsIn(s) { continue }
      idleIssued.insert(key)
      if away { signal("claude_idle", s, minutes: Int((now - d) / 60)) }
      reminders.removeAll { $0.sessionId == s.id && $0.key.hasPrefix("done") }
      addReminder(Reminder(key: key, text: "💤 idle \(Int((now - d) / 60))m: \(where_(s))", sessionId: s.id, hostPid: s.hostPid, sticky: true, created: now))
    }
    // break nudge after 50 min streak
    if streakStart > 0 && now - lastEventAt < 600 && now - streakStart > 50 * 60 && now - lastBreakNudge > 50 * 60 {
      lastBreakNudge = now
      addReminder(Reminder(key: "break", text: "☕ 50 min straight. Stretch, water, eyes off the screen?", sessionId: nil, hostPid: 0, sticky: false, created: now))
    }
    // non-sticky reminders expire after 25 s
    reminders.removeAll { !$0.sticky && now - $0.created > 25 }
    computeDisplay(now: now)
  }

  func computeDisplay(now: Double) {
    let live = sessions.values.filter { $0.state != "ended" && now - $0.ts < 30 * 60 }
    let primary = live.max { $0.ts < $1.ts }
    var state = "sleep", text: String? = nil
    var pid = 0
    if let p = primary {
      state = p.state; pid = p.hostPid
      displayTool = p.raw["tool"] as? String ?? ""
      shownStateKey = "\(p.id)|\(p.ts)"
      if state == "done", let d = p.doneAt, now - d > 20 { state = "idle" }
      if state == "idle", now - p.ts > 5 * 60 { state = "sleep" }
      if state == "error", now - p.ts > 8 { state = "working" }
      switch state {
      case "thinking" where showActivity: text = "\(p.project): thinking…"
      case "working" where showActivity: text = "\(p.project): \(p.detail)"
      case "waiting" where showActivity: text = "\(p.project): \(p.detail.isEmpty ? "waiting for you" : p.detail)"
      case "done": text = "\(p.project): done ✓" + summaryLine(p.project)
      case "error" where showActivity: text = "\(p.project): hmm, \(p.detail)"
      default: break
      }
    }
    if !shownStateKey.isEmpty && shownStateKey == dismissedStateKey { text = nil }   // user dismissed this status line
    if live.contains(where: { $0.state == "waiting" }) && state != "waiting" { state = "waiting" }
    if let r = reminders.last {
      pid = r.hostPid
      text = r.text + (r.key.hasPrefix("done:") ? summaryLine(sessions[r.sessionId ?? ""]?.project ?? "") : "")
      if state == "sleep" || state == "idle" || state == "done" { state = r.key.hasPrefix("done") ? "done" : "remind" }
      let verb = r.key.hasPrefix("hub:") ? "click: done" : "click: open"
      text! += reminders.count > 1 ? "\n(+\(reminders.count - 1) more · \(verb) · ⌃Space: dismiss)" : "\n(\(verb) · ⌃Space or right-click: dismiss)"
    }
    if let t = transientText, now < transientUntil { text = t; if state == "sleep" { state = "idle" } }
    if state == "sleep" && !reminders.isEmpty { state = "remind" }
    bubblePid = pid
    if state != displayState { displayState = state; frameIdx = 0 }
    bot.sleeping = state == "sleep"
    setBubble(text)
  }

  func summaryLine(_ project: String) -> String {
    guard let m = readJSON(PROJECTS + "/" + safe(project) + ".json"), let doing = m["doing"] as? String, !doing.isEmpty,
          (Date().timeIntervalSince1970 - (m["ts"] as? Double ?? 0)) < 600 else { return "" }
    return "\n" + doing
  }

  func setBubble(_ text: String?) {
    let want = text ?? ""
    if want.isEmpty {
      if bubble.alphaValue > 0 { NSAnimationContext.runAnimationGroup { $0.duration = 0.35; bubble.animator().alphaValue = 0 } }
      return
    }
    if want != bubble.full {
      // babble the new line, unless the real voice is about to read it
      if bot.rig != nil, (readJSON(CONFIG_DIR + "/config.json")?["babble"] as? Bool) ?? true {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in
          guard let self, !self.voice.isSpeaking, self.sfxAllowed() else { return }
          self.audio.babble(want.components(separatedBy: "\n").first ?? want)
        }
      }
      bubble.set(want)
      let size = bubble.layoutFor(maxWidth: W - 30)
      let bx = max(4, bot.frame.minX + 30 - size.width)
      bubble.frame = NSRect(x: bx, y: bot.frame.maxY - 14, width: size.width, height: size.height)
      if bubble.alphaValue < 1 {
        bubble.setFrameOrigin(NSPoint(x: bx, y: bot.frame.maxY - 24))
        NSAnimationContext.runAnimationGroup { ctx in
          ctx.duration = 0.3; bubble.animator().alphaValue = 1
          bubble.animator().setFrameOrigin(NSPoint(x: bx, y: bot.frame.maxY - 14))
        }
      }
    }
  }

  func say(_ text: String, for secs: Double) { transientText = text; transientUntil = Date().timeIntervalSince1970 + secs; computeDisplay(now: Date().timeIntervalSince1970) }

  func addReminder(_ r: Reminder) {
    reminders.removeAll { $0.key == r.key }
    reminders.append(r)
    let live = NSWorkspace.shared.frontmostApplication?.processIdentifier ?? 0
    log("remind: \(r.key) host=\(r.hostPid) front=\(frontPid)/\(live) text=\(r.text.prefix(80))")
    if !hidden { NSSound(named: "Tink")?.play() }
    maybeSpeak(r)
  }

  /// What Jester says out loud for a reminder (nil = stay silent). Project names only, never code or prompts.
  func spokenLine(_ r: Reminder) -> String? {
    let proj = sessions[r.sessionId ?? ""]?.project.replacingOccurrences(of: "[-_]", with: " ", options: .regularExpression) ?? ""
    let p = proj.isEmpty ? "your session" : proj
    if r.key.hasPrefix("done:") { return voice.pick("done", [
      "Huzzah! \(p) hath finished, my liege!", "Ta-da! \(p) is done, and I didn't drop a single ball.",
      "Hear ye, hear ye! \(p) is complete!", "Done and dusted in \(p). Applause, please.",
      "\(p) is finished! I'd bow, but my hat would fall off.", "Behold! \(p) is done. Your move, your majesty."]) }
    if r.key.hasPrefix("wait:") { return voice.pick("wait", [
      "Psst, my liege! \(p) awaits your royal nod.", "A decree is needed! \(p) wants your OK.",
      "Ahem. \(p) stands frozen until you say yes.", "Your jester begs a moment: \(p) needs permission."]) }
    if r.key.hasPrefix("idle:") { return voice.pick("idle", [
      "\(p) finished long ago and sits alone. Even jesters get lonely.",
      "Psst. \(p) finished a while back. Shall I juggle until you return?",
      "The court of \(p) awaits. It finished ages ago."]) }
    if r.key.hasPrefix("hub:") { return voice.pick("hub", ["Hear ye! A royal reminder: ", "Pardon the bells, my liege. Reminder: "]) + Voice.plain(r.text) }
    if r.key == "break" { return voice.pick("break", [
      "Fifty minutes of toil! Stretch, sip some water, rest thine eyes.",
      "Even kings take breaks. Stand up, stretch, drink some water."]) }
    return nil
  }
  func voiceAllowed(replies: Bool = false) -> Bool {
    let m = voice.mode
    if m == "off" || hidden || Voice.quiet() { return false }
    if replies { return m == "always" }
    // "away": only when you are not at the keyboard
    return m == "always" || userIdleSeconds() > 60
  }
  func maybeSpeak(_ r: Reminder) {
    guard voiceAllowed(), let line = spokenLine(r) else { return }
    log("voice: \(r.key)")
    voice.speak(line, key: r.key)
  }
  func speakReply(_ reply: String) {
    guard voiceAllowed(replies: true) else { return }
    voice.speak(reply, key: "reply:\(reply.hashValue)", minGap: 0)
  }

  // MARK: resume / parked memory (per project)
  func safe(_ s: String) -> String { s.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression) }
  func resumeReminder(for s: Session) {
    guard let m = readJSON(PROJECTS + "/" + safe(s.project) + ".json") else { return }
    let now = Date().timeIntervalSince1970
    let age = now - (m["ts"] as? Double ?? now)
    guard age > 300 else { return }
    var t = "↩︎ \(s.project), last time (\(ago(age))): \(m["doing"] as? String ?? "?")"
    if let nx = m["next"] as? String, !nx.isEmpty { t += "\n→ next: \(nx)" }
    if m["unfinished"] as? Bool == true { t += "\n⚑ parked as unfinished" }
    addReminder(Reminder(key: "resume:\(s.project)", text: t, sessionId: s.id, hostPid: s.hostPid, sticky: false, created: now))
  }
  func markParked(project: String, cwd: String, why: String) {
    let p = PROJECTS + "/" + safe(project) + ".json"
    var m = readJSON(p) ?? ["doing": why, "ts": Date().timeIntervalSince1970]
    m["unfinished"] = true; m["cwd"] = cwd; m["project"] = project
    writeJSON(p, m)
  }
  func ago(_ s: Double) -> String { s < 3600 ? "\(Int(s / 60))m ago" : s < 86400 ? "\(Int(s / 3600))h ago" : "\(Int(s / 86400))d ago" }

  // MARK: Haiku summarizer (runs here, not in the hook, so hooks stay fast)
  func summarize(_ s: Session) {
    guard let bin = claudeBin, !summarizing.contains(s.id) else { return }
    let pfile = PROJECTS + "/" + safe(s.project) + ".json"
    let prev = readJSON(pfile)
    if let ts = prev?["ts"] as? Double, Date().timeIntervalSince1970 - ts < 120, prev?["session"] as? String == s.id { clearNeedsSummary(s.id); return }
    let prompts = (s.raw["prompts"] as? [String] ?? []).joined(separator: "\n- ")
    let reply = s.raw["last_reply"] as? String ?? ""
    let ask = """
    You label a developer's coding session for a desktop reminder bot. Project folder: \(s.project).
    Recent user prompts:
    - \(prompts)
    Assistant's last reply (truncated): \(reply)
    Reply with ONLY compact JSON: {"doing": "<=60 chars, what the user is working on", "next": "<=60 chars, the obvious next step or empty", "unfinished": true|false}
    """
    summarizing.insert(s.id)
    let proc = Process()
    proc.executableURL = URL(fileURLWithPath: bin)
    proc.arguments = ["-p", "--model", "haiku", "--no-session-persistence", "--setting-sources", "", "--tools", "", "--", ask]
    var e = ProcessInfo.processInfo.environment; e["JESTER_CHILD"] = "1"; e.removeValue(forKey: "CLAUDECODE")
    proc.environment = e
    proc.currentDirectoryURL = URL(fileURLWithPath: CACHE)
    let out = Pipe(); proc.standardOutput = out; proc.standardError = FileHandle.nullDevice
    proc.standardInput = FileHandle.nullDevice
    let sid = s.id, project = s.project, cwd = s.cwd
    proc.terminationHandler = { [weak self] p in
      let data = out.fileHandleForReading.readDataToEndOfFile()
      DispatchQueue.main.async {
        guard let self else { return }
        self.summarizing.remove(sid); self.clearNeedsSummary(sid)
        let txt = String(data: data, encoding: .utf8) ?? ""
        guard p.terminationStatus == 0, let a = txt.firstIndex(of: "{"), let b = txt.lastIndex(of: "}"),
              let j = (try? JSONSerialization.jsonObject(with: Data(txt[a...b].utf8))) as? [String: Any] else {
          log("summary failed status=\(p.terminationStatus) out=\(txt.prefix(200))"); return
        }
        var m: [String: Any] = ["project": project, "cwd": cwd, "session": sid, "ts": Date().timeIntervalSince1970]
        m["doing"] = String((j["doing"] as? String ?? "").prefix(80)); m["next"] = String((j["next"] as? String ?? "").prefix(80))
        m["unfinished"] = j["unfinished"] as? Bool ?? false
        writeJSON(PROJECTS + "/" + self.safe(project) + ".json", m)
        log("summary ok: \(project)")
        self.bubble.full = ""  // force bubble refresh with the summary line
      }
    }
    do { try proc.run()
      DispatchQueue.main.asyncAfter(deadline: .now() + 45) { if proc.isRunning { proc.terminate(); log("summary timeout") } }
    } catch { summarizing.remove(sid); log("summary spawn failed: \(error)") }
  }
  func clearNeedsSummary(_ id: String) {
    let p = SESSIONS + "/" + id + ".json"
    guard var d = readJSON(p), d["needs_summary"] != nil else { return }
    // only rewrite if the hook has not written since (avoid clobbering a newer event)
    d.removeValue(forKey: "needs_summary")
    if let cur = readJSON(p), (cur["ts"] as? Double) == (d["ts"] as? Double) { writeJSON(p, d) }
  }

  // MARK: animation + mouse
  func animate() {
    if bot.rig != nil { bot.setFrameOrigin(NSPoint(x: bot.frame.origin.x, y: 0)); bubble.tick(); return }
    let now = Date().timeIntervalSince1970
    let frames = pack.anim(displayState)
    guard !frames.isEmpty else { return }
    let fps = displayState == "sleep" ? 1.5 : (displayState == "working" ? pack.fps * 1.5 : pack.fps)
    if now - lastFrameAt >= 1 / fps { frameIdx = (frameIdx + 1) % frames.count; lastFrameAt = now }
    bot.image = frames[frameIdx % frames.count]
    let amp = displayState == "sleep" ? pack.bob * 0.5 : pack.bob * (displayState == "waiting" || displayState == "remind" ? 2.2 : 1)
    let speed = displayState == "waiting" || displayState == "remind" ? 4.0 : 1.8
    let y = CGFloat(sin((now - t0) * speed)) * amp * pack.scale / 2
    bot.setFrameOrigin(NSPoint(x: bot.frame.origin.x, y: max(0, y + amp * pack.scale / 2)))
    let g = displayState == "waiting" || displayState == "remind" ? 0.55 + 0.35 * CGFloat(abs(sin((now - t0) * 3))) : 0.6
    if abs(g - bot.lastGlow) > 0.05 { bot.glowStrength = g; bot.lastGlow = g; bot.needsDisplay = true }
    bubble.tick()
  }
  func updateMouseThrough() {
    guard !hidden else { panel.ignoresMouseEvents = true; return }
    let m = NSEvent.mouseLocation, o = panel.frame.origin
    let local = NSPoint(x: m.x - o.x, y: m.y - o.y)
    let overBot = bot.frame.insetBy(dx: 10, dy: 10).contains(local)
    let overBubble = bubble.alphaValue > 0.5 && bubble.frame.contains(local)
    panel.ignoresMouseEvents = !(overBot || overBubble)
  }

  // MARK: actions
  @objc func frontChanged(_ n: Notification) {
    let app = n.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
    frontPid = app?.processIdentifier ?? 0
    // you went to the session's app -> its done/wait reminders are served
    reminders.removeAll { $0.hostPid > 0 && $0.hostPid == Int(frontPid) && ($0.key.hasPrefix("done") || $0.key.hasPrefix("wait") || $0.key.hasPrefix("idle")) }
  }
  /// Right-click on the bubble: drop the top reminder (or the current message) without jumping or marking done.
  func dismissTop() {
    voice.stop()
    if let r = reminders.popLast() {
      if r.key.hasPrefix("hub:") { dismissedHub.insert(String(r.key.dropFirst(4))) }
      log("dismiss: \(r.key)")
    }
    else if transientUntil > Date().timeIntervalSince1970 { transientUntil = 0; log("dismiss: message") }
    else { dismissedStateKey = shownStateKey; log("dismiss: status line") }
    computeDisplay(now: Date().timeIntervalSince1970)
  }

  func bubbleClicked() {
    if let r = reminders.popLast() {
      if r.key.hasPrefix("hub:") { enqueue(["kind": "done", "id": String(r.key.dropFirst(4))]); log("click: hub done \(r.key)"); hubSync() }
      else { log("click: reminder \(r.key) -> pid \(r.hostPid)"); jump(pid: r.hostPid) }
    }
    else { log("click: bubble (no reminder) -> pid \(bubblePid)"); jump(pid: bubblePid) }
    computeDisplay(now: Date().timeIntervalSince1970)
  }
  func botClicked() {
    if !reminders.isEmpty { bubbleClicked(); return }
    if hubURL() == nil { jump(pid: bubblePid); return }   // no hub: clicking the bot jumps to the latest session
    openAsk()
  }

  // MARK: ask box (needs the optional hub): plain English to your reminders and memory, answered by local Claude or the hub's chat
  @objc func openAsk() {
    if askPanel == nil {
      let w: CGFloat = 380, h: CGFloat = 46
      let p = AskPanel(contentRect: NSRect(x: 0, y: 0, width: w, height: h), styleMask: [.borderless], backing: .buffered, defer: false)
      p.becomesKeyOnlyIfNeeded = false; p.hidesOnDeactivate = false
      p.isOpaque = false; p.backgroundColor = .clear; p.level = .statusBar; p.hasShadow = true
      p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
      let fx = NSVisualEffectView(frame: NSRect(x: 0, y: 0, width: w, height: h))
      fx.material = .hudWindow; fx.blendingMode = .behindWindow; fx.state = .active
      fx.wantsLayer = true; fx.layer?.cornerRadius = 12; fx.layer?.borderWidth = 1.5
      fx.layer?.borderColor = pack.accent.withAlphaComponent(0.85).cgColor
      askField.frame = NSRect(x: 14, y: 11, width: w - 28, height: 24)
      askField.isBordered = false; askField.drawsBackground = false; askField.focusRingType = .none
      askField.font = NSFont.systemFont(ofSize: 14)
      askField.textColor = NSColor(srgbRed: 1, green: 236/255, blue: 232/255, alpha: 1)
      askField.placeholderString = "Ask Jester… \"call mom 6pm\", \"what's left today?\""
      askField.target = self; askField.action = #selector(askSubmit)
      askField.delegate = self
      fx.addSubview(askField); p.contentView = fx
      askPanel = p
    }
    guard let p = askPanel else { return }
    let f = panel.frame
    p.setFrameOrigin(NSPoint(x: max(8, f.maxX - p.frame.width - 8), y: f.minY + bot.frame.maxY + 10))
    appBeforeAsk = NSWorkspace.shared.frontmostApplication
    askField.stringValue = ""
    // take real keyboard focus: activate this (Dock-less) app, then focus the field
    if #available(macOS 14.0, *) { NSApp.activate() } else { NSApp.activate(ignoringOtherApps: true) }
    NSApp.activate(ignoringOtherApps: true)
    p.makeKeyAndOrderFront(nil)
    p.orderFrontRegardless()
    p.makeFirstResponder(askField)
    askTyped = false
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { log("ask: opened key=\(p.isKeyWindow) active=\(NSApp.isActive)") }
  }

  func controlTextDidChange(_ obj: Notification) {
    if !askTyped { askTyped = true; log("ask: typing received") }
  }

  func closeAsk(restoreFocus: Bool) {
    askPanel?.orderOut(nil)
    if restoreFocus, let a = appBeforeAsk, a.processIdentifier != getpid() { a.activate() }
  }

  @objc func askSubmit() {
    let text = askField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
    log("ask: submit chars=\(text.count)")
    guard !text.isEmpty else { closeAsk(restoreFocus: true); return }
    closeAsk(restoreFocus: true)
    guard hubURL() != nil else { say("Ask needs the optional hub: jester hub setup <url> (see docs/HUB_API.md)", for: 8); return }
    say("💭 …", for: 45)
    let key = "mac-ask-\(Int(Date().timeIntervalSince1970 * 1000))"
    let askAI = (readJSON(CONFIG_DIR + "/config.json")?["ask_ai"] as? String) ?? "claude"
    if askAI == "claude", let bin = claudeBin {
      askLocalClaude(text, bin: bin, key: key) { [weak self] ok in if !ok { self?.askHub(text, key: key) } }
      return
    }
    askHub(text, key: key)
  }

  /// Mac brain: local Claude (Haiku, `claude -p`) answers as Jester with the cached open items and facts,
  /// and returns actions that are applied through the hub. Open items and facts are sent to your Claude account.
  func askLocalClaude(_ text: String, bin: String, key: String, done: @escaping (Bool) -> Void) {
    let now = Date()
    let df = DateFormatter(); df.dateFormat = "yyyy-MM-dd HH:mm EEEE"
    let items = openItems.map { "- [\($0["id"] ?? "")] \($0["title"] ?? "")\((($0["due"] as? String) ?? "").isEmpty ? "" : " (due \($0["due"]!))")" }.joined(separator: "\n")
    let facts = factsCache.map { "- \($0)" }.joined(separator: "\n")
    let t0 = Date()
    if claudeSession == nil { claudeSession = ClaudeSession(bin: bin) }
    let context = "Open items:\n\(items.isEmpty ? "(none)" : items)\nKnown facts:\n\(facts.isEmpty ? "(none)" : facts)"
    let head = "Now: \(df.string(from: now)) (\(TimeZone.current.identifier)).\n"
    claudeSession?.ask(head: head, context: context, tail: "The user says: \(text)", delta: { [weak self] partial in
      if !partial.isEmpty { self?.say(partial, for: 30) }
    }, done: { [weak self] finalText in
      guard let self else { return }
      guard let txt = finalText, let a = txt.firstIndex(of: "{"), let b = txt.lastIndex(of: "}"),
            let j = (try? JSONSerialization.jsonObject(with: Data(txt[a...b].utf8))) as? [String: Any],
            let reply = j["reply"] as? String else {
        log("ask: claude session failed, falling back to hub"); done(false); return
      }
      self.applyClaude(j, reply: reply, key: key, t0: t0)
      done(true)
    })
  }

  func applyClaude(_ j: [String: Any], reply: String, key: String, t0: Date) {
    do {
      let list = { (k: String) in ((j[k] as? [Any]) ?? []).compactMap { $0 as? String }.filter { !$0.isEmpty } }
      let adds = list("add"), dones = list("done"), rems = list("remember"), forgets = list("forget")
      log("ask: claude ok \(Int(Date().timeIntervalSince(t0) * 1000))ms add=\(adds.count) done=\(dones.count) remember=\(rems.count) forget=\(forgets.count)")
      say(reply, for: min(20, 6 + Double(reply.count) / 15))
      speakReply(reply)
      // apply actions through the hub (offline-safe: adds/dones fall back to the outbox)
      hubQ.async { [weak self] in
        for (i, t) in adds.enumerated() {
          let (c, _) = hubCall("POST", "/api/inbox", ["text": t, "key": "\(key)-a\(i)"])
          if !(200..<300).contains(c) { enqueue(["kind": "inbox", "text": t]) }
        }
        let known = Set((self?.openItems ?? []).compactMap { $0["id"] as? String })
        for id in dones where known.contains(id) {
          let (c, _) = hubCall("POST", "/api/items/\(id)/done")
          if !(200..<300).contains(c) { enqueue(["kind": "done", "id": id]) }
        }
        for f in rems { _ = hubCall("POST", "/api/memory", ["fact": f, "source": "mac"]) }
        for f in forgets { _ = hubCall("POST", "/api/memory/forget", ["match": f]) }
        if !adds.isEmpty || !dones.isEmpty { DispatchQueue.main.async { self?.lastRingingPull = 0; self?.hubSync() } }
      }
    }
  }

  /// Hub brain: used when ask_ai = "hub", or when the local Claude fails.
  func askHub(_ text: String, key: String) {
    hubQ.async { [weak self] in
      let (code, data) = hubCall("POST", "/api/chat", ["text": text, "key": key])
      var reply = "Couldn't reach the hub; saved it to send later."
      if code == 200, let d = data, let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
        reply = (j["reply"] as? String) ?? "Done."
        let acts = (j["actions"] as? [[String: Any]]) ?? []
        log("ask: chat ok ai=\(j["ai"] ?? "?") actions=\(acts.count)")
      } else {
        enqueue(["kind": "inbox", "text": text])   // never lose it: plain capture later
        log("ask: chat failed \(code), queued as inbox")
      }
      DispatchQueue.main.async { self?.say(reply, for: min(20, 6 + Double(reply.count) / 15)); self?.speakReply(reply); self?.lastRingingPull = 0; self?.hubSync() }
    }
  }
  func buildMenu() -> NSMenu {
    let m = NSMenu()
    let parked = ((try? fm.contentsOfDirectory(atPath: PROJECTS)) ?? []).compactMap { readJSON(PROJECTS + "/" + $0) }.filter { $0["unfinished"] as? Bool == true }
    let ph = NSMenuItem(title: parked.isEmpty ? "No parked work" : "Parked work", action: nil, keyEquivalent: ""); ph.isEnabled = false; m.addItem(ph)
    for p in parked { let i = NSMenuItem(title: "  \(p["project"] ?? "?"): \(p["doing"] ?? "")", action: #selector(clearParked(_:)), keyEquivalent: ""); i.target = self; i.representedObject = p["project"]; i.toolTip = "click to mark done"; m.addItem(i) }
    m.addItem(.separator())
    let now = Date().timeIntervalSince1970
    let live = sessions.values.filter { $0.state != "ended" && now - $0.ts < 12 * 3600 }.sorted { $0.ts > $1.ts }
    let sh = NSMenuItem(title: live.isEmpty ? "No live sessions" : "Sessions (click to jump)", action: nil, keyEquivalent: ""); sh.isEnabled = false; m.addItem(sh)
    for s in live.prefix(12) {
      let st = s.state == "done" || s.state == "idle" ? "idle \(ago(now - (s.doneAt ?? s.ts)))" : s.state
      let i = NSMenuItem(title: "  \(where_(s)) · \(st)", action: #selector(jumpTo(_:)), keyEquivalent: ""); i.target = self; i.representedObject = s.hostPid; m.addItem(i)
    }
    m.addItem(.separator())
    let bots = NSMenuItem(title: "Bot", action: nil, keyEquivalent: ""); let sub = NSMenu()
    var names = Set(((try? fm.contentsOfDirectory(atPath: USER_BOTS)) ?? []).filter { fm.fileExists(atPath: USER_BOTS + "/" + $0 + "/bot.json") })
    if let res = Bundle.main.resourcePath { names.formUnion((try? fm.contentsOfDirectory(atPath: res + "/bots")) ?? []) }
    for n in names.sorted() { let i = NSMenuItem(title: n, action: #selector(pickBot(_:)), keyEquivalent: ""); i.target = self; i.state = n == packName ? .on : .off; sub.addItem(i) }
    bots.submenu = sub; m.addItem(bots)
    let clr = NSMenuItem(title: "Clear reminders", action: #selector(clearReminders), keyEquivalent: ""); clr.target = self; m.addItem(clr)
    let ask = NSMenuItem(title: "Ask Jester… (⌃⌥T)", action: #selector(openAsk), keyEquivalent: ""); ask.target = self; m.insertItem(ask, at: 0); m.insertItem(.separator(), at: 1)
    let hide = NSMenuItem(title: "Hide (⌃⌥J)", action: #selector(toggleHidden), keyEquivalent: ""); hide.target = self; m.addItem(hide)
    let q = NSMenuItem(title: "Quit until next login", action: #selector(quit), keyEquivalent: ""); q.target = self; m.addItem(q)
    return m
  }
  @objc func clearParked(_ i: NSMenuItem) {
    guard let pr = i.representedObject as? String else { return }
    let p = PROJECTS + "/" + safe(pr) + ".json"; if var d = readJSON(p) { d["unfinished"] = false; writeJSON(p, d) }
  }
  @objc func pickBot(_ i: NSMenuItem) {
    var c = readJSON(CONFIG_DIR + "/config.json") ?? [:]; c["bot"] = i.title; writeJSON(CONFIG_DIR + "/config.json", c); loadConfig()
  }
  @objc func jumpTo(_ i: NSMenuItem) { if let p = i.representedObject as? Int { jump(pid: p) } }
  @objc func clearReminders() { reminders.removeAll() }
  @objc func toggleHidden() {
    hidden.toggle()
    NSAnimationContext.runAnimationGroup { $0.duration = 0.25; panel.animator().alphaValue = hidden ? 0 : 1 }
  }
  @objc func quit() { log("quit from menu"); exit(0) }  // LaunchAgent KeepAlive only restarts on crash

  func registerHotKey() {
    var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
    InstallEventHandler(GetApplicationEventTarget(), { _, ev, ud in
      let me = Unmanaged<Mascot>.fromOpaque(ud!).takeUnretainedValue()
      var hk = EventHotKeyID()
      GetEventParameter(ev, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID), nil, MemoryLayout<EventHotKeyID>.size, nil, &hk)
      DispatchQueue.main.async { if hk.id == 2 { me.openAsk() } else if hk.id == 3 { me.dismissTop() } else { me.toggleHidden() } }
      return noErr
    }, 1, &spec, Unmanaged.passUnretained(self).toOpaque(), nil)
    let id = EventHotKeyID(signature: OSType(0x4A535452), id: 1)  // 'JSTR'
    RegisterEventHotKey(UInt32(kVK_ANSI_J), UInt32(controlKey | optionKey), id, GetApplicationEventTarget(), 0, &hotKeyRef)
    let id2 = EventHotKeyID(signature: OSType(0x4A535452), id: 2)
    RegisterEventHotKey(UInt32(kVK_ANSI_T), UInt32(controlKey | optionKey), id2, GetApplicationEventTarget(), 0, &hotKeyRef2)
    // extra Ask shortcut from config.json "ask_hotkey" (default "" = off; e.g. "cmd+shift+space").
    let askSpec = (readJSON(CONFIG_DIR + "/config.json")?["ask_hotkey"] as? String) ?? ""
    if let (code, mods) = parseHotkey(askSpec) {
      let id3 = EventHotKeyID(signature: OSType(0x4A535452), id: 2)   // same action as ⌃⌥T
      let st = RegisterEventHotKey(code, mods, id3, GetApplicationEventTarget(), 0, &hotKeyRef3)
      log("ask hotkey \(askSpec): \(st == noErr ? "registered" : "FAILED \(st) (taken by another app?)")")
    }
    // dismiss the current notification from anywhere: config.json "dismiss_hotkey" (default "ctrl+space"; "" disables)
    let dismissSpec = (readJSON(CONFIG_DIR + "/config.json")?["dismiss_hotkey"] as? String) ?? "ctrl+space"
    if let (code, mods) = parseHotkey(dismissSpec) {
      let id4 = EventHotKeyID(signature: OSType(0x4A535452), id: 3)
      let st = RegisterEventHotKey(code, mods, id4, GetApplicationEventTarget(), 0, &hotKeyRef4)
      log("dismiss hotkey \(dismissSpec): \(st == noErr ? "registered" : "FAILED \(st)")")
    }
  }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
// A menu-less (Dock-less) app has no Edit menu, so ⌘V/⌘C/⌘X/⌘A/⌘Z never reach text fields.
// Wispr Flow and other dictation tools insert text by pasting, so install a (never shown) Edit menu.
do {
  let main = NSMenu(), editItem = NSMenuItem(), edit = NSMenu(title: "Edit")
  edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
  edit.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
  edit.addItem(.separator())
  edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
  edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
  edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
  edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
  editItem.submenu = edit; main.addItem(editItem); app.mainMenu = main
}
let delegate = Mascot()
app.delegate = delegate
app.run()
