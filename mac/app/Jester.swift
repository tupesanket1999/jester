// Jester.swift: procedural vector jester (two-bone IK limbs, spring-physics hat tails, juggling, particles).
// Drawn every display frame by BotView when the active pack has "rig": "jester". No sprites: every pose is a
// function of time, and state changes cross-fade poses, so motion stays smooth at 60-144 Hz.
import AppKit

// MARK: colours (Crimson Nova)
private func rgb(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1) -> CGColor {
  CGColor(srgbRed: r / 255, green: g / 255, blue: b / 255, alpha: a)
}
private enum JC {
  static let crimson = rgb(230, 57, 70), red = rgb(255, 77, 94), rose = rgb(232, 130, 140), blush = rgb(242, 167, 160)
  static let amber = rgb(224, 165, 92), ink = rgb(18, 11, 13), skin = rgb(253, 233, 221), cream = rgb(255, 244, 236)
  static let gold = rgb(246, 196, 92), goldDark = rgb(170, 112, 38), crimsonDark = rgb(150, 30, 44)
  static let amberDark = rgb(176, 116, 50), mouth = rgb(96, 16, 26), wood = rgb(122, 74, 52), white = rgb(255, 255, 255)
  static let sweat = rgb(150, 205, 255)
  static let confetti = [crimson, amber, rose, gold, red, blush]
}

// MARK: math
private func + (a: CGPoint, b: CGPoint) -> CGPoint { CGPoint(x: a.x + b.x, y: a.y + b.y) }
private func - (a: CGPoint, b: CGPoint) -> CGPoint { CGPoint(x: a.x - b.x, y: a.y - b.y) }
private func * (a: CGPoint, s: CGFloat) -> CGPoint { CGPoint(x: a.x * s, y: a.y * s) }
private func P(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }
private func len(_ p: CGPoint) -> CGFloat { hypot(p.x, p.y) }
private func norm(_ p: CGPoint) -> CGPoint { let l = len(p); return l < 1e-6 ? P(0, 1) : p * (1 / l) }
private func mixf(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat { a + (b - a) * t }
private func mixp(_ a: CGPoint, _ b: CGPoint, _ t: CGFloat) -> CGPoint { P(mixf(a.x, b.x, t), mixf(a.y, b.y, t)) }
private func clamp(_ v: CGFloat, _ lo: CGFloat = 0, _ hi: CGFloat = 1) -> CGFloat { min(hi, max(lo, v)) }
private func smooth(_ t: CGFloat) -> CGFloat { let x = clamp(t); return x * x * (3 - 2 * x) }
private func easeOut(_ t: CGFloat) -> CGFloat { let x = 1 - clamp(t); return 1 - x * x * x }
private func easeIn(_ t: CGFloat) -> CGFloat { let x = clamp(t); return x * x * x }
/// progress of t through [a, b], clamped to 0...1
private func seg(_ t: CGFloat, _ a: CGFloat, _ b: CGFloat) -> CGFloat { clamp((t - a) / (b - a)) }
private func osc(_ t: CGFloat, _ hz: CGFloat, _ ph: CGFloat = 0) -> CGFloat { sin(2 * .pi * hz * t + ph) }
private func fract(_ x: CGFloat) -> CGFloat { x - floor(x) }
private func bez(_ a: CGPoint, _ c: CGPoint, _ b: CGPoint, _ t: CGFloat) -> CGPoint {
  let u = 1 - t; return a * (u * u) + c * (2 * u * t) + b * (t * t)
}
/// Two-bone IK: returns (joint, end). bend = -1 bends to the left of the root->target line, +1 to the right.
private func ik(_ root: CGPoint, _ target: CGPoint, _ l1: CGFloat, _ l2: CGFloat, _ bend: CGFloat) -> (CGPoint, CGPoint) {
  let d = target - root
  let dist = clamp(len(d), abs(l1 - l2) + 0.01, (l1 + l2) * 0.999)
  let cosA = clamp((l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist), -1, 1)
  let ang = atan2(d.y, d.x) + bend * acos(cosA)
  let joint = root + P(cos(ang), sin(ang)) * l1
  return (joint, root + norm(d) * dist)
}

// MARK: pose
/// Everything that moves. Hands, props and balls live in torso space (origin = hip, rotated with lean);
/// feet live in ground space unless feetAttach pulls them into body space (jumps, flips).
struct JPose {
  var root = CGPoint.zero, rot: CGFloat = 0, squash: CGFloat = 1, hipY: CGFloat = 44, lean: CGFloat = 0
  var headTilt: CGFloat = 0, headNod: CGFloat = 0
  var handL = CGPoint(x: -19, y: 6), handR = CGPoint(x: 19, y: 6)
  var footL = CGPoint(x: -11, y: 0), footR = CGPoint(x: 11, y: 0), feetAttach: CGFloat = 0
  var eyeOpen: CGFloat = 1, happy: CGFloat = 0, dizzy: CGFloat = 0, mouthOpen: CGFloat = 0.1, smile: CGFloat = 0.6
  var brow: CGFloat = 0, tongue: CGFloat = 0, look = CGPoint.zero, followMouse: CGFloat = 0
  var marotte: CGFloat = 0, marotteAngle: CGFloat = 0.12, marotteGrip: CGFloat = 0.3
  var orb: CGFloat = 0, scroll: CGFloat = 0, scrollPos = CGPoint(x: -3, y: 16), writeProgress: CGFloat = 4
  var quill: CGFloat = 0, card: CGFloat = 0, cardFlip: CGFloat = 0
  var balls: CGFloat = 0, ball0 = CGPoint.zero, ball1 = CGPoint.zero, ball2 = CGPoint.zero
  var alert: CGFloat = 0, thought: CGFloat = 0, sweat: CGFloat = 0, stars: CGFloat = 0, glow: CGFloat = 0.45, droop: CGFloat = 0

  static let scalars: [WritableKeyPath<JPose, CGFloat>] = [
    \.rot, \.squash, \.hipY, \.lean, \.headTilt, \.headNod, \.feetAttach, \.eyeOpen, \.happy, \.dizzy, \.mouthOpen, \.smile,
    \.brow, \.tongue, \.followMouse, \.marotte, \.marotteAngle, \.marotteGrip, \.orb, \.scroll, \.writeProgress, \.quill,
    \.card, \.cardFlip, \.balls, \.alert, \.thought, \.sweat, \.stars, \.glow, \.droop]
  static let points: [WritableKeyPath<JPose, CGPoint>] = [
    \.root, \.handL, \.handR, \.footL, \.footR, \.look, \.scrollPos, \.ball0, \.ball1, \.ball2]
  static func mix(_ a: JPose, _ b: JPose, _ t: CGFloat) -> JPose {
    if t <= 0 { return a }; if t >= 1 { return b }
    var r = a
    for k in scalars { r[keyPath: k] = mixf(a[keyPath: k], b[keyPath: k], t) }
    for k in points { r[keyPath: k] = mixp(a[keyPath: k], b[keyPath: k], t) }
    return r
  }
}

/// Plays phases back to back with a short cross-fade at each boundary so nothing pops.
private func timeline(_ t: CGFloat, _ phases: [(CGFloat, (CGFloat) -> JPose)], xf: CGFloat = 0.09) -> JPose {
  var i = 0
  while i + 1 < phases.count && t >= phases[i + 1].0 { i += 1 }
  let cur = phases[i].1(t)
  guard i > 0, t - phases[i].0 < xf else { return cur }
  return JPose.mix(phases[i - 1].1(t), cur, smooth((t - phases[i].0) / xf))
}

// MARK: particles
private struct Particle {
  enum Kind { case confetti, sparkle, zzz, note, puff, card, ink }
  var kind: Kind; var p: CGPoint; var v: CGPoint; var age: CGFloat = 0; var life: CGFloat
  var rot: CGFloat = 0; var spin: CGFloat = 0; var size: CGFloat; var color: CGColor
}

private struct Tail {
  var base = CGPoint.zero, mid = CGPoint.zero, tip = CGPoint.zero
  var vMid = CGPoint.zero, vTip = CGPoint.zero
  var l1: CGFloat, l2: CGFloat, color: CGColor, lastJingle: Double = 0
  var ready = false
}

// MARK: rig
final class JesterRig {
  private(set) var key = "sleep"
  private var keyStart = CACurrentMediaTime()
  private var prevKey = "sleep", prevStart = CACurrentMediaTime(), blendStart = 0.0, blendDur = 0.0
  private var requested = "sleep"
  private var fired: Set<String> = []
  private var trick = -1, trickStart = 0.0, nextTrick = CACurrentMediaTime() + 6
  private var blinkAt = CACurrentMediaTime() + 2, nextBlink = CACurrentMediaTime() + 3
  private var lastSpawn: [String: Double] = [:]
  fileprivate var particles: [Particle] = []
  fileprivate var tails: [Tail] = [
    Tail(l1: 13, l2: 15, color: JC.amber), Tail(l1: 13, l2: 14, color: JC.red), Tail(l1: 13, l2: 15, color: JC.amber)]
  fileprivate var pose = JPose()
  private var faceW = CGPoint(x: 0, y: 98)
  private var mouse: CGPoint?        // world space
  var zoom: CGFloat = 0.8
  /// sound cue for a moment in the choreography ("whoosh", "boing", "tada", "giggle", "slide", "thud", "jingle", "tinkle", "flick")
  var onSound: ((String) -> Void)?
  /// The app draws the glow as a GPU radial-gradient layer (a CPU gradient is ~30% of the frame); offline renders set this.
  var drawGlow = false
  private(set) var glowCenter = CGPoint(x: 0, y: 68), glowAlpha: CGFloat = 0.45
  var accent: CGColor = JC.crimson

  // geometry caches filled by solve(), read by draw()
  fileprivate var T = CGAffineTransform.identity, Tt = CGAffineTransform.identity, Th = CGAffineTransform.identity
  fileprivate var now = CACurrentMediaTime()

  // MARK: state requests
  static func key(for state: String, tool: String) -> String {
    switch state {
    case "working":
      switch tool {
      case "Edit", "Write", "MultiEdit", "NotebookEdit": return "working:scribe"
      case "Read", "Grep", "Glob", "LS", "WebFetch", "WebSearch", "ToolSearch": return "working:read"
      case "Bash", "BashOutput": return "working:fast"
      default: return "working:juggle"
      }
    case "idle", "thinking", "waiting", "done", "error", "remind", "sleep": return state
    default: return "idle"
    }
  }
  /// true while something fast is on screen (juggling, flips, hops, tricks, cross-fades): the app runs 120 Hz then, 60 Hz otherwise.
  var wantsHighFPS: Bool {
    key.hasPrefix("working") || ["waiting", "done", "error"].contains(key) || (key == "idle" && trick >= 0)
      || now - blendStart < blendDur || particles.count > 8
  }
  func request(state: String, tool: String) { requested = JesterRig.key(for: state, tool: tool) }

  private func applyRequest() {
    guard requested != key else { return }
    let urgent = ["waiting", "error", "done", "remind"].contains(requested)
    let shown = now - keyStart
    let bothWork = key.hasPrefix("working") && requested.hasPrefix("working")
    // tools flip every second during a run; hold a juggling/scribing variant long enough to read
    if !urgent && shown < (bothWork ? 2.5 : 0.6) { return }
    prevKey = key; prevStart = keyStart
    key = requested; keyStart = now; blendStart = now
    blendDur = prevKey == "error" ? 0.75 : (key == "done" ? 0.18 : (key == "sleep" || prevKey == "sleep" ? 0.9 : 0.32))
    fired = []
    if key == "idle" { trick = -1; nextTrick = now + Double.random(in: 5...10) }
  }

  // MARK: per-frame update
  /// windowDelta: how far the panel moved since the last frame (screen points) so the hat swings while dragged.
  func step(time: Double, windowDelta: CGPoint, mouseLocal: CGPoint?, viewSize: CGSize) {
    let dt = CGFloat(min(1.0 / 20, max(1.0 / 240, time - now)))
    now = time
    applyRequest()
    if let m = mouseLocal { mouse = toWorld(m, viewSize) } else { mouse = nil }

    // idle tricks
    if key == "idle" {
      if trick >= 0 && now - trickStart > trickLength(trick) { trick = -1; nextTrick = now + Double.random(in: 8...18) }
      if trick < 0 && now > nextTrick && now - keyStart > 1.5 { trick = Int.random(in: 0...5); trickStart = now; fired.remove("trick") }
    }
    // blinks
    if now > nextBlink {
      blinkAt = now
      nextBlink = now + (Double.random(in: 0...1) < 0.2 ? 0.28 : Double.random(in: 2.2...5.5))
    }

    var p = poseFor(key, CGFloat(now - keyStart))
    let bw = blendDur > 0 ? CGFloat((now - blendStart) / blendDur) : 1
    if bw < 1 { p = JPose.mix(poseFor(prevKey, CGFloat(now - prevStart)), p, smooth(bw)) }
    // blink (not while eyes are already shut or smiling)
    let bk = CGFloat(now - blinkAt)
    if bk < 0.14 && p.happy < 0.5 && p.dizzy < 0.5 { p.eyeOpen *= 1 - sin(.pi * bk / 0.14) }
    // eyes (and a touch of head) follow the cursor
    if p.followMouse > 0, let m = mouse {
      let d = m - faceW, l = len(d)
      let target = norm(d) * min(1, l / 140)
      p.look = mixp(p.look, target, p.followMouse)
      p.headTilt += -0.07 * p.followMouse * clamp(d.x / 300, -1, 1)
    }
    pose = p
    solve()
    events(CGFloat(now - keyStart))
    simulateTails(dt, windowDelta: windowDelta * (1 / zoom))
    simulateParticles(dt)
  }

  private func toWorld(_ local: CGPoint, _ size: CGSize) -> CGPoint {
    P((local.x - size.width / 2) / zoom, (local.y - (10 + 12 * zoom)) / zoom)
  }

  private func solve() {
    let p = pose
    T = CGAffineTransform(translationX: p.root.x, y: p.root.y).translatedBy(x: 0, y: 52).rotated(by: p.rot).translatedBy(x: 0, y: -52)
    Tt = T.translatedBy(x: 0, y: p.hipY).rotated(by: p.lean)
    Th = Tt.translatedBy(x: 0, y: 37).rotated(by: p.headTilt).translatedBy(x: 0, y: 17 + p.headNod)
    faceW = P(0, 0).applying(Th)
    glowCenter = P(0, 68).applying(T); glowAlpha = clamp(p.glow)
  }

  // MARK: choreography
  private func trickLength(_ i: Int) -> Double { [1.5, 2.6, 2.5, 3.4, 2.3, 2.6][max(0, min(5, i))] }

  private func standing(_ t: CGFloat) -> JPose {
    var p = JPose()
    p.squash = 1 + 0.014 * osc(t, 0.3)
    p.root.x = 2 * osc(t, 0.12); p.lean = -0.04 * osc(t, 0.12); p.headTilt = 0.06 * osc(t, 0.12, 0.8)
    p.handL = P(-19, 6 + 1.2 * osc(t, 0.3, 0.4)); p.handR = P(18, 8 + 1.2 * osc(t, 0.3, 0.9))
    p.marotte = 1; p.marotteAngle = 0.12 + 0.05 * osc(t, 0.25); p.marotteGrip = 0.3
    p.followMouse = 0.85
    return p
  }

  private func poseFor(_ k: String, _ t: CGFloat) -> JPose {
    switch k {
    case "idle": return idle(t)
    case "thinking": return thinking(t)
    case "working:juggle": return juggling(t, beat: 0.27, height: 92)
    case "working:fast": return juggling(t, beat: 0.2, height: 78)
    case "working:scribe": return scribe(t)
    case "working:read": return reading(t)
    case "waiting": return waiting(t)
    case "remind": return remind(t)
    case "done": return done(t)
    case "error": return error(t)
    default: return sleep(t)
    }
  }

  private func idle(_ t: CGFloat) -> JPose {
    let base = standing(t)
    guard key == "idle", trick >= 0 else { return base }
    let k = CGFloat(now - trickStart), L = CGFloat(trickLength(trick))
    let w = smooth(k / 0.3) * smooth((L - k) / 0.3)
    var p = base
    switch trick {
    case 0: // hat jingle: shake the head so the bells ring
      let env = sin(.pi * clamp(k / L))
      p.headTilt += 0.26 * osc(k, 5.5) * env; p.root.y += 2 * abs(osc(k, 2.75)) * env
      p.happy = 1; p.smile = 1; p.mouthOpen = 0.45; p.followMouse = 0
    case 1: // shuffle dance
      let b = osc(k, 2)
      p.root.y = 3.5 * abs(b); p.root.x += 4 * osc(k, 1)
      p.footL = P(-11 + 2 * osc(k, 1), max(0, 7 * b)); p.footR = P(11 + 2 * osc(k, 1), max(0, -7 * b))
      p.handL = P(-21, 16 + 9 * b); p.handR = P(21, 16 - 9 * b); p.marotteAngle = 0.4 * b
      p.lean = 0.08 * osc(k, 1); p.headTilt = -0.12 * osc(k, 2)
      p.happy = 1; p.smile = 1; p.mouthOpen = 0.35; p.followMouse = 0
    case 2: // card flourish with the left hand
      let up = smooth(seg(k, 0, 0.5)), flick = seg(k, 1.35, 1.6)
      p.handL = mixp(base.handL, P(-11, 32), up)
      if flick > 0 { p.handL = mixp(P(-11, 32), P(-25, 42), easeOut(flick)) }
      if k > 1.6 { p.handL = mixp(P(-25, 42), base.handL, smooth(seg(k, 1.6, 2.3))) }
      p.card = k < 1.45 ? up : 0; p.cardFlip = k * 9
      p.look = P(-0.6, 0.1); p.followMouse = 0; p.brow = 0.5; p.smile = 0.9; p.headTilt = 0.1
    case 3: // quick juggle
      p = juggling(k, beat: 0.26, height: 84); p.marotte = 0
    case 4: // shade the eyes and peek at the cursor
      var dir: CGFloat = 1
      if let m = mouse { dir = m.x >= 0 ? 1 : -1 }
      p.root.x += 4 * dir; p.lean = -0.13 * dir; p.headTilt = -0.1 * dir
      p.handL = P(-10, 54); p.brow = 0.8; p.followMouse = 1; p.smile = 0.4; p.mouthOpen = 0.2
    default: // big yawn and stretch
      let s = sin(.pi * clamp(k / L))
      p.handL = P(-11, 60); p.handR = P(11, 60); p.marotte = 0
      p.squash = 1 + 0.07 * s; p.eyeOpen = 0.15; p.mouthOpen = 0.9; p.smile = 0.1; p.followMouse = 0
      p.headTilt = 0.1 * osc(k, 0.5)
    }
    return JPose.mix(base, p, w)
  }

  private func thinking(_ t: CGFloat) -> JPose {
    var p = standing(t)
    p.marotte = 0; p.followMouse = 0
    p.lean = 0.05; p.headTilt = 0.12 + 0.05 * osc(t, 0.25)
    p.handL = P(-14, 22 + 1.5 * osc(t, 0.5)); p.orb = 1
    p.handR = P(6 + 1.2 * osc(t, 1.3), 40); p.headNod = -0.5
    let g = smooth(0.5 + 0.5 * sin(t * 2 * .pi / 6) * 3)   // alternate: gaze at the orb, then up
    p.look = mixp(P(-0.65, -0.55), P(0.55, 0.8), g)
    p.brow = 0.55; p.smile = 0.05; p.mouthOpen = 0.04; p.thought = 1; p.glow = 0.55
    return p
  }

  /// Three-ball cascade. Each ball: flight 2.1 beats, dwell 0.9 beats in the catching hand; hands alternate throws.
  private func juggling(_ t: CGFloat, beat: CGFloat, height: CGFloat) -> JPose {
    var p = standing(t)
    p.marotte = 0; p.balls = 1; p.followMouse = 0
    let T = t / beat, D: CGFloat = 0.9, F: CGFloat = 3 - D
    let throwPt = [P(-8, 15), P(8, 15)], catchPt = [P(-27, 21), P(27, 21)], scoop = [P(-17, 8), P(17, 8)]
    var held: [CGPoint?] = [nil, nil]
    var balls: [CGPoint] = []
    for i in 0..<3 {
      let u = (T - CGFloat(i)).truncatingRemainder(dividingBy: 3), uu = u < 0 ? u + 3 : u
      let throwBeat = Int(floor(T - uu + 0.0001))
      let h = ((throwBeat % 2) + 2) % 2, o = 1 - h
      if uu < F {
        let s = uu / F
        let a = throwPt[h], b = catchPt[o]
        balls.append(P(mixf(a.x, b.x, s), mixf(a.y, b.y, s) + 4 * height * s * (1 - s)))
      } else {
        let s = (uu - F) / D
        let b = bez(catchPt[o], scoop[o], throwPt[o], s)
        balls.append(b); held[o] = b
      }
    }
    for h in 0..<2 {
      if let b = held[h] { let hp = b - P(0, 4); if h == 0 { p.handL = hp } else { p.handR = hp }; continue }
      let parity = CGFloat(h)
      var last = floor(T); if Int(last) % 2 != Int(parity) { last -= 1 }
      let s = clamp((T - last) / (2 - D))
      let hp = bez(throwPt[h], (throwPt[h] + catchPt[h]) * 0.5 + P(0, 9), catchPt[h], smooth(s)) - P(0, 4)
      if h == 0 { p.handL = hp } else { p.handR = hp }
    }
    p.ball0 = balls[0]; p.ball1 = balls[1]; p.ball2 = balls[2]
    let top = balls.max { $0.y < $1.y }!
    p.look = norm(top - P(0, 54)) * 0.9
    p.headTilt = -0.004 * top.x; p.root.y = 1.2 * abs(sin(.pi * T)); p.lean = 0.03 * osc(t, 0.4)
    p.tongue = 1; p.smile = 0.35; p.brow = 0.3; p.mouthOpen = 0.08
    return p
  }

  private func scribe(_ t: CGFloat) -> JPose {
    var p = standing(t)
    p.marotte = 0; p.followMouse = 0; p.scroll = 1; p.quill = 1
    p.scrollPos = P(-3, 15); p.handL = P(-17, 14)
    let cyc: CGFloat = 1.05, lines = t / cyc, line = floor(lines).truncatingRemainder(dividingBy: 5), s = fract(lines)
    p.writeProgress = line + s
    p.handR = P(-13 + 20 * s, 23 - 4 * min(line, 3) + 1.3 * osc(t, 7)) + P(3, 2)
    p.look = P(-0.1 + 0.5 * s, -0.85); p.headTilt = -0.1; p.headNod = -1.5
    p.tongue = 1; p.brow = 0.25; p.smile = 0.3
    return p
  }

  private func reading(_ t: CGFloat) -> JPose {
    var p = standing(t)
    p.marotte = 0; p.followMouse = 0; p.scroll = 1; p.writeProgress = 4
    p.scrollPos = P(0, 22); p.handL = P(-17, 22); p.handR = P(17, 22)
    let lines = t / 0.9, s = fract(lines), line = floor(lines).truncatingRemainder(dividingBy: 4)
    p.look = P(-0.7 + 1.4 * smooth(s), -0.25 - 0.18 * line); p.headTilt = 0.05 * (s - 0.5)
    p.brow = 0.4; p.smile = 0.15; p.mouthOpen = 0.12
    return p
  }

  private func hop(_ t: CGFloat, _ height: CGFloat, into p: inout JPose) {
    // anticipation squash -> airborne stretch -> landing squash
    if t < 0.14 { let c = sin(.pi * t / 0.14); p.hipY -= 6 * c; p.squash -= 0.12 * c }
    else if t < 0.5 {
      let s = (t - 0.14) / 0.36
      p.root.y += height * 4 * s * (1 - s); p.squash += 0.07 * (1 - s); p.feetAttach = 0.85 * sin(.pi * s)
      p.footL = P(-9, 3); p.footR = P(9, 3)
    } else if t < 0.62 { let c = sin(.pi * (t - 0.5) / 0.12); p.hipY -= 5 * c; p.squash -= 0.13 * c }
  }

  private func waiting(_ t: CGFloat) -> JPose {
    var p = standing(t)
    let period: CGFloat = t < 6 ? 0.9 : 2.2
    hop(t.truncatingRemainder(dividingBy: period), t < 6 ? 20 : 14, into: &p)
    p.handR = P(14 + 8 * osc(t, 2.2), 60); p.marotteAngle = 0.4 * osc(t, 2.2, 0.6); p.marotteGrip = 0.22
    p.handL = P(-21, 32 + 7 * osc(t, 2.2, .pi))
    p.eyeOpen = 1.15; p.brow = 0.8; p.mouthOpen = 0.55; p.smile = 0.2; p.followMouse = 1; p.alert = 1
    p.glow = 0.5 + 0.4 * abs(sin(3 * t))
    return p
  }

  private func remind(_ t: CGFloat) -> JPose {
    var p = standing(t)
    p.marotte = 0
    hop(t.truncatingRemainder(dividingBy: 2.6), 9, into: &p)
    p.handR = P(20 + 6 * osc(t, 1.6), 54); p.handL = P(-17, 13)
    p.happy = t < 1.2 ? 1 : 0; p.smile = 0.95; p.mouthOpen = 0.35; p.followMouse = 1; p.brow = 0.4
    p.glow = 0.5 + 0.3 * abs(sin(2.2 * t))
    return p
  }

  private func done(_ t: CGFloat) -> JPose {
    let base = standing(t)
    return timeline(t, [
      (0, { _ in var p = base; p.marotte = 0
        p.hipY = 34; p.squash = 0.82; p.handL = P(-21, -3); p.handR = P(21, -3); p.brow = 0.5; p.smile = 1; p.mouthOpen = 0.3
        p.followMouse = 0; return p }),
      (0.22, { t in var p = base; p.marotte = 0
        let s = seg(t, 0.22, 0.86)
        p.root.y = 48 * 4 * s * (1 - s); p.rot = 2 * .pi * smooth(s); p.squash = s < 0.15 ? 1.12 : 1
        p.feetAttach = 1; let tuck = sin(.pi * clamp(s * 1.15))
        p.footL = mixp(P(-11, 0), P(-7, 24), tuck); p.footR = mixp(P(11, 0), P(7, 24), tuck)
        p.handL = mixp(P(-24, 40), P(-11, -2), tuck); p.handR = mixp(P(24, 40), P(11, -2), tuck)
        p.happy = 1; p.mouthOpen = 0.6; p.smile = 1; p.followMouse = 0; return p }),
      (0.86, { t in var p = base; p.marotte = 0
        let l = seg(t, 0.86, 1.12)
        p.squash = 1 - 0.22 * sin(.pi * l); p.hipY = 44 - 9 * sin(.pi * l)
        p.footL = P(-14, 0); p.footR = P(14, 0); p.handL = P(-27, 22); p.handR = P(27, 22)
        p.happy = 1; p.smile = 1; p.mouthOpen = 0.5; p.followMouse = 0; return p }),
      (1.12, { t in var p = base; p.marotte = 0
        p.footL = P(-14, 0); p.footR = P(14, 0); p.handL = P(-24, 58); p.handR = P(24, 58)
        p.root.y = 2 * sin(.pi * seg(t, 1.12, 1.9))
        p.happy = 1; p.smile = 1; p.mouthOpen = 0.7; p.followMouse = 0; p.glow = 0.8; return p }),
      (1.9, { t in var p = base; p.marotte = 0
        let s = seg(t, 1.9, 3.1), b = sin(.pi * s)
        p.hipY = 44 - 6 * b; p.headNod = -8 * b; p.headTilt = 0.18 * b; p.squash = 1 - 0.06 * b
        p.handR = bez(P(22, 52), P(14, 30), P(2, 12), smooth(seg(t, 1.9, 2.4)))
        if s > 0.6 { p.handR = mixp(P(2, 12), P(20, 8), smooth((s - 0.6) / 0.4)) }
        p.handL = P(-29, 20); p.eyeOpen = 0; p.happy = 1; p.smile = 1; p.mouthOpen = 0.15; p.followMouse = 0; return p }),
      (3.1, { t in var p = base
        let u = t - 3.1, amp = CGFloat(exp(-Double(u) / 4))
        self.hop(u.truncatingRemainder(dividingBy: 0.75), 10 * amp, into: &p)
        p.handL = mixp(base.handL, P(-21, 30 + 24 * max(0, osc(u, 0.7))), amp)
        p.handR = mixp(base.handR, P(21, 30 + 24 * max(0, -osc(u, 0.7))), amp)
        p.marotte = 1 - amp
        p.happy = u < 8 ? 1 : 0; p.smile = 1; p.mouthOpen = 0.2 + 0.4 * amp; p.glow = 0.45 + 0.3 * amp; return p }),
    ], xf: 0.07)
  }

  private func error(_ t: CGFloat) -> JPose {
    let base = standing(t)
    func sitting(_ t: CGFloat) -> JPose {
      var p = base; p.marotte = 0; p.followMouse = 0
      p.hipY = 15; p.root.x = 0; p.footL = P(-25, 0); p.footR = P(25, 0)
      p.lean = 0.09 * osc(t, 0.7); p.headTilt = 0.16 * osc(t, 0.9, 1)
      p.handL = P(-25, -2); p.handR = P(25, -2)
      p.dizzy = 1; p.stars = 1; p.sweat = 1; p.smile = -0.35; p.mouthOpen = 0.2; p.brow = -0.6
      return p
    }
    return timeline(t, [
      (0, { _ in var p = base; p.marotte = 0
        p.handL = P(-24, 48); p.handR = P(24, 48); p.brow = -0.8; p.eyeOpen = 1.2; p.mouthOpen = 0.75; p.smile = -0.5
        p.squash = 1.05; p.followMouse = 0; return p }),
      (0.18, { t in var p = base; p.marotte = 0
        let s = seg(t, 0.18, 0.56)
        p.root.y = 16 * sin(.pi * s); p.rot = 0.4 * s; p.hipY = mixf(44, 22, easeIn(s))
        p.feetAttach = 1; p.footL = P(-10, mixf(0, 34, sin(.pi * s))); p.footR = P(16, mixf(0, 40, sin(.pi * s)))
        let a = 2 * .pi * 3 * s
        p.handL = P(-18 + 9 * cos(a), 34 + 9 * sin(a)); p.handR = P(18 - 9 * cos(a), 34 - 9 * sin(a))
        p.eyeOpen = 1.25; p.mouthOpen = 0.9; p.smile = -0.6; p.brow = -1; p.followMouse = 0; return p }),
      (0.56, { t in var p = sitting(t); p.dizzy = 0; p.stars = 0
        let l = seg(t, 0.56, 0.78); p.squash = 1 - 0.24 * sin(.pi * l); p.eyeOpen = 0.2; p.mouthOpen = 0.5; return p }),
      (0.78, sitting),
    ], xf: 0.08)
  }

  private func sleep(_ t: CGFloat) -> JPose {
    var p = JPose()
    p.hipY = 41; p.lean = -0.08; p.root.x = -2; p.squash = 1 + 0.02 * osc(t, 0.22)
    p.headTilt = -0.32 + 0.03 * osc(t, 0.22); p.headNod = -3
    p.eyeOpen = 0; p.mouthOpen = 0.18 + 0.1 * osc(t, 0.22); p.smile = 0.1
    p.handR = P(17, 10); p.marotte = 1; p.marotteAngle = 0.04; p.marotteGrip = 0.95
    p.handL = P(-18, 3); p.droop = 1; p.glow = 0.15
    return p
  }

  // MARK: one-shot effects
  private func once(_ id: String, at: CGFloat, _ t: CGFloat, _ f: () -> Void) {
    if t >= at && !fired.contains(id) { fired.insert(id); f() }
  }
  private func every(_ id: String, _ secs: Double, _ f: () -> Void) {
    if now - (lastSpawn[id] ?? 0) >= secs { lastSpawn[id] = now; f() }
  }
  private func events(_ t: CGFloat) {
    let head = P(0, 20).applying(Th)
    switch key {
    case "done":
      once("takeoff", at: 0.2, t) { onSound?("whoosh") }
      once("giggle", at: 1.35, t) { onSound?("giggle") }
      once("land", at: 0.86, t) {
        onSound?("boing")
        for _ in 0..<44 {
          let a = CGFloat.random(in: 0.25 * .pi...0.75 * .pi), sp = CGFloat.random(in: 130...330)
          particles.append(Particle(kind: .confetti, p: P(CGFloat.random(in: -12...12), 30), v: P(cos(a), sin(a)) * sp,
                                    life: CGFloat.random(in: 1.4...2.4), rot: .random(in: 0...6), spin: .random(in: -14...14),
                                    size: .random(in: 3...5.5), color: JC.confetti.randomElement()!))
        }
        puff(at: P(-14, 2)); puff(at: P(14, 2))
      }
      once("tada", at: 1.15, t) {
        onSound?("tada"); for _ in 0..<6 { sparkle(at: P(0, 70) + P(.random(in: -40...40), .random(in: -10...40))) } }
      if t > 3 && t < 9 { every("cele", 0.5) { sparkle(at: P(.random(in: -45...45), .random(in: 40...120))) } }
    case "error":
      once("slip", at: 0.2, t) { onSound?("slide") }
      once("thud", at: 0.58, t) {
        onSound?("thud"); puff(at: P(-10, 3)); puff(at: P(10, 3)); puff(at: P(0, 2)) }
    case "sleep":
      every("z", 1.7) {
        particles.append(Particle(kind: .zzz, p: head + P(10, 6), v: P(14, 18), life: 2.6, rot: 0.2, spin: 0.1, size: 9, color: JC.rose))
      }
    case "waiting":
      once("ask", at: 0.05, t) { onSound?("tinkle") }
    case "remind":
      once("hello", at: 0.05, t) { onSound?("jingle") }
      every("note", 1.3) { note(at: head + P(-18, 4)) }
    case "thinking":
      every("orb", 0.45) { sparkle(at: pose.handL.applying(Tt) + P(.random(in: -10...10), .random(in: 8...24)), small: true) }
    case "working:scribe":
      every("ink", 0.35) {
        particles.append(Particle(kind: .ink, p: pose.handR.applying(Tt), v: P(.random(in: -20...20), .random(in: 10...40)),
                                  life: 0.5, size: 1.6, color: JC.ink))
      }
    case "idle" where trick >= 0:
      let k = CGFloat(now - trickStart)
      if trick == 0 { once("trick", at: 0.1, k) { onSound?("jingle"); note(at: head + P(-16, 6)); note(at: head + P(16, 10)) } }
      if trick == 2 {
        once("trick", at: 1.45, k) {
          onSound?("flick")
          particles.append(Particle(kind: .card, p: pose.handL.applying(Tt), v: P(-120, 230), life: 1.1, rot: 0, spin: 14,
                                    size: 7, color: JC.white))
        }
      }
      if trick == 5 { once("trick", at: 0.8, k) { sparkle(at: head + P(0, 10), small: true) } }
    default: break
    }
  }
  private func puff(at p: CGPoint) {
    for _ in 0..<4 {
      particles.append(Particle(kind: .puff, p: p, v: P(.random(in: -40...40), .random(in: 6...30)), life: 0.6,
                                size: .random(in: 4...7), color: rgb(235, 220, 215, 0.9)))
    }
  }
  private func sparkle(at p: CGPoint, small: Bool = false) {
    particles.append(Particle(kind: .sparkle, p: p, v: P(0, 10), life: small ? 0.6 : 0.9, rot: .random(in: 0...1), spin: 2,
                              size: small ? 3.5 : 6, color: [JC.gold, JC.blush, JC.white].randomElement()!))
  }
  private func note(at p: CGPoint) {
    particles.append(Particle(kind: .note, p: p, v: P(.random(in: -12...12), 34), life: 1.6, rot: .random(in: -0.3...0.3),
                              size: 11, color: [JC.crimson, JC.amber, JC.rose].randomElement()!))
  }

  // MARK: physics
  private func simulateTails(_ dt: CGFloat, windowDelta: CGPoint) {
    let d = pose.droop
    // base (head space) and rest offsets for mid and tip; droop bends them down for sleep
    let rest: [(CGPoint, CGPoint, CGPoint)] = [
      (P(-9, 16), mixp(P(-13, 8), P(-11, -2), d), mixp(P(-25, 0), P(-17, -14), d)),
      (P(0, 19), mixp(P(1, 13), P(4, 8), d), mixp(P(7, 25), P(15, 4), d)),
      (P(9, 16), mixp(P(13, 8), P(11, -2), d), mixp(P(25, 0), P(17, -14), d)),
    ]
    let steps = max(1, Int(ceil(dt / (1.0 / 240))))
    let h = dt / CGFloat(steps)
    for i in 0..<3 {
      var tl = tails[i]
      let base = rest[i].0.applying(Th)
      let mT = (rest[i].0 + rest[i].1).applying(Th), tT = (rest[i].0 + rest[i].2).applying(Th)
      if !tl.ready { tl.mid = mT; tl.tip = tT; tl.ready = true }
      tl.base = base
      tl.mid = tl.mid - windowDelta; tl.tip = tl.tip - windowDelta   // inertia when the window is dragged
      for _ in 0..<steps {
        let g = P(0, -70)
        let aM = (mT - tl.mid) * 240 - tl.vMid * 13 + g
        let aT = (tT - tl.tip) * 120 - tl.vTip * 8 + g
        tl.vMid = tl.vMid + aM * h; tl.vTip = tl.vTip + aT * h
        tl.mid = tl.mid + tl.vMid * h; tl.tip = tl.tip + tl.vTip * h
        tl.mid = base + norm(tl.mid - base) * tl.l1
        tl.tip = tl.mid + norm(tl.tip - tl.mid) * tl.l2
      }
      if len(tl.vTip) > 280 && now - tl.lastJingle > 0.18 {
        tl.lastJingle = now; onSound?("tinkle"); sparkle(at: tl.tip + P(.random(in: -4...4), .random(in: -4...4)), small: true)
      }
      tails[i] = tl
    }
  }

  private func simulateParticles(_ dt: CGFloat) {
    for i in particles.indices {
      var q = particles[i]
      q.age += dt
      switch q.kind {
      case .confetti: q.v = q.v * CGFloat(pow(0.12, Double(dt))) + P(0, -260) * dt; q.v.x += 30 * sin(q.age * 9 + q.rot) * dt
      case .card: q.v = q.v + P(0, -380) * dt
      case .ink: q.v = q.v + P(0, -200) * dt
      case .puff: q.v = q.v * CGFloat(pow(0.05, Double(dt)))
      case .note, .zzz: q.v.x += 18 * sin(q.age * 4) * dt
      case .sparkle: break
      }
      q.p = q.p + q.v * dt; q.rot += q.spin * dt
      particles[i] = q
    }
    particles.removeAll { $0.age >= $0.life || $0.p.y < -20 }
    if particles.count > 160 { particles.removeFirst(particles.count - 160) }
  }

  // MARK: frame data shared by both renderers (live GPU layers and offline CG renders stay identical)
  fileprivate struct Limb { var a: CGPoint; var j: CGPoint; var b: CGPoint; var color: CGColor; var w: CGFloat; var l1: CGFloat; var l2: CGFloat }
  fileprivate struct Item { var xf: CGAffineTransform; var alpha: CGFloat; static let none = Item(xf: .identity, alpha: 0) }
  fileprivate struct Frame {
    var legs: [Limb] = [], shoes: [CGAffineTransform] = [], arms: [Limb] = [], sleeves: [CGAffineTransform] = []
    var hands: [CGAffineTransform] = [], tailBells: [CGAffineTransform] = []
    var squash = CGAffineTransform.identity, shadow = Item.none
    var marotte = Item.none, orb = Item.none, swirl = Item.none, scroll = Item.none, quill = Item.none, card = Item.none
    var cardFront = true
    var balls: [Item] = [], dots: [Item] = [], stars: [Item] = [], alert = Item.none, sweat = Item.none
  }

  fileprivate func frame() -> Frame {
    let p = pose, t = CGFloat(now)
    var f = Frame()
    for (side, foot, col) in [(CGFloat(-1), p.footL, JC.amber), (CGFloat(1), p.footR, JC.crimson)] {
      let hip = P(8 * side, p.hipY - 3).applying(T)
      let fw = mixp(foot, foot.applying(T), p.feetAttach)
      let footRot = atan2(T.b, T.a) * p.feetAttach, up = P(-sin(footRot), cos(footRot))
      let (knee, end) = ik(hip, fw + up * 4, 20, 21, side)
      f.legs.append(Limb(a: hip, j: knee, b: end, color: col, w: 7.5, l1: 20, l2: 21))
      let sp = end - up * 4
      f.shoes.append(TR(sp).rotated(by: footRot).scaledBy(x: -side, y: 1))
    }
    let shL = P(-13.5, 29).applying(Tt), shR = P(13.5, 29).applying(Tt)
    let (eL, hL) = ik(shL, p.handL.applying(Tt), 15.5, 15.5, -1), (eR, hR) = ik(shR, p.handR.applying(Tt), 15.5, 15.5, 1)
    f.arms = [Limb(a: shL, j: eL, b: hL, color: JC.crimson, w: 6.5, l1: 15.5, l2: 15.5),
              Limb(a: shR, j: eR, b: hR, color: JC.amber, w: 6.5, l1: 15.5, l2: 15.5)]
    f.sleeves = [TR(shL), TR(shR)]; f.hands = [TR(hL), TR(hR)]
    f.squash = CGAffineTransform(scaleX: 1 / sqrt(max(0.4, p.squash)), y: p.squash)
    let air = clamp(p.root.y / 70), sw = 52 * (1 - 0.45 * air) * (p.hipY < 25 ? 1.3 : 1)
    f.shadow = Item(xf: TR(P(p.root.x, 0)).scaledBy(x: sw / 52, y: 1), alpha: 0.24 * (1 - 0.6 * air))
    for tl in tails { let d = norm(tl.tip - tl.mid); f.tailBells.append(TR(tl.tip + d * 3).rotated(by: atan2(d.y, d.x) + .pi / 2)) }
    let rot = atan2(Tt.b, Tt.a)
    let a = p.marotteAngle + rot, dir = P(-sin(a), cos(a))
    f.marotte = Item(xf: TR(hR - dir * (46 * p.marotteGrip)).rotated(by: a), alpha: p.marotte)
    let oc = hL + P(0, 11 + 1.5 * sin(t * 2.2))
    f.orb = Item(xf: TR(oc), alpha: p.orb); f.swirl = Item(xf: TR(oc).rotated(by: t * 2.1), alpha: p.orb)
    f.scroll = Item(xf: Tt.translatedBy(x: p.scrollPos.x, y: p.scrollPos.y), alpha: p.scroll)
    f.quill = Item(xf: TR(hR).rotated(by: rot - 0.55), alpha: p.quill)
    let cf = cos(p.cardFlip); f.cardFront = cf >= 0
    f.card = Item(xf: TR(hL + P(0, 7)).rotated(by: rot + 0.2).scaledBy(x: max(0.08, abs(cf)), y: 1), alpha: p.card)
    f.balls = [p.ball0, p.ball1, p.ball2].map { Item(xf: Tt.translatedBy(x: $0.x, y: $0.y), alpha: p.balls) }
    let s = max(0.01, p.alert * (1 + 0.12 * abs(sin(t * 5))))
    f.alert = Item(xf: TR(P(0, 44 + 2.5 * abs(sin(t * 5))).applying(Th)).scaledBy(x: s, y: s), alpha: p.alert > 0.02 ? 1 : 0)
    f.dots = (0..<3).map { k in
      let ph = fract(t * 0.6 + CGFloat(k) / 3), r = 1.5 + ph * 3
      return Item(xf: TR(P(16 + CGFloat(k) * 3 + ph * 6, 22 + ph * 22).applying(Th)).scaledBy(x: r / 4.5, y: r / 4.5), alpha: p.thought * sin(.pi * ph))
    }
    let ph = fract(t * 0.7)
    f.sweat = Item(xf: TR(P(17, 9 - ph * 8).applying(Th)), alpha: p.sweat * (1 - ph * 0.6))
    let sc = P(0, 24).applying(Th)
    f.stars = (0..<3).map { k in
      let an = t * 4 + CGFloat(k) * 2 * .pi / 3
      return Item(xf: TR(sc + P(cos(an) * 21, sin(an) * 5.5)).rotated(by: t * 3), alpha: p.stars * (sin(an) < 0 ? 1 : 0.55))
    }
    return f
  }

  fileprivate func particleItem(_ q: Particle) -> (Item, Bool) {
    let k = q.age / q.life
    switch q.kind {
    case .confetti: return (Item(xf: TR(q.p).rotated(by: q.rot).scaledBy(x: cos(q.age * 8 + q.rot) * q.size, y: q.size), alpha: 1 - easeIn(k)), true)
    case .sparkle:
      let s = q.size * (0.6 + 0.4 * sin(.pi * k)) / 6
      return (Item(xf: TR(q.p).rotated(by: q.rot).scaledBy(x: s, y: s), alpha: sin(.pi * k)), true)
    case .puff: let r = q.size * (0.6 + 1.2 * easeOut(k)) / 7; return (Item(xf: TR(q.p).scaledBy(x: r, y: r), alpha: 0.8 * (1 - k)), true)
    case .ink: return (Item(xf: TR(q.p).scaledBy(x: q.size / 2, y: q.size / 2), alpha: 1 - k), true)
    case .card:
      let c = cos(q.rot)
      return (Item(xf: TR(q.p).rotated(by: q.rot * 0.3).scaledBy(x: max(0.08, abs(c)), y: 1), alpha: 1 - easeIn(k)), c >= 0)
    case .zzz, .note:
      let sz = q.size * (q.kind == .zzz ? 0.7 + 0.6 * k : 1) / 12
      return (Item(xf: TR(q.p).rotated(by: q.rot).scaledBy(x: sz, y: sz), alpha: sin(.pi * k)), true)
    }
  }
  /// local drawing of a particle at its canonical size (sparkle r 6, puff r 7, ink r 2, glyphs 12 pt, confetti unit rect)
  fileprivate func particleLocal(_ ctx: CGContext, _ kind: Particle.Kind, _ color: CGColor, front: Bool) {
    switch kind {
    case .confetti: ctx.setFillColor(color); ctx.fill(CGRect(x: -0.5, y: -0.3, width: 1, height: 0.6))
    case .sparkle:
      let sp = CGMutablePath()
      for i in 0..<8 { let a = CGFloat(i) * .pi / 4, rr: CGFloat = i % 2 == 0 ? 6 : 1.7; let pt = P(cos(a), sin(a)) * rr; i == 0 ? sp.move(to: pt) : sp.addLine(to: pt) }
      sp.closeSubpath(); ctx.addPath(sp); ctx.setFillColor(color); ctx.fillPath()
    case .puff: ctx.setFillColor(color); ctx.fillEllipse(in: CGRect(x: -7, y: -7, width: 14, height: 14))
    case .ink: ctx.setFillColor(color); ctx.fillEllipse(in: CGRect(x: -2, y: -2, width: 4, height: 4))
    case .card: cardLocal(ctx, front: front)
    case .zzz, .note:
      let s = NSAttributedString(string: kind == .zzz ? "z" : "♪", attributes: [
        .font: NSFont.systemFont(ofSize: 12, weight: .heavy), .foregroundColor: NSColor(cgColor: color) ?? .systemPink,
        .strokeColor: NSColor.black, .strokeWidth: -3])
      ctx.textPosition = .zero; CTLineDraw(CTLineCreateWithAttributedString(s), ctx)
    }
  }

  // MARK: CG renderer (offline sheets/GIFs; the app uses JesterLayers)
  func draw(in ctx: CGContext, size: CGSize) {
    let f = frame()
    ctx.saveGState()
    ctx.setLineJoin(.round); ctx.setLineCap(.round)
    ctx.translateBy(x: size.width / 2, y: 10 + 12 * zoom)
    ctx.scaleBy(x: zoom, y: zoom)
    if drawGlow, let g = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB),
                                    colors: [accent.copy(alpha: 0.32 * glowAlpha)!, accent.copy(alpha: 0)!] as CFArray, locations: [0, 1]) {
      ctx.drawRadialGradient(g, startCenter: glowCenter, startRadius: 0, endCenter: glowCenter, endRadius: 95, options: [])
    }
    at(ctx, f.shadow) { $0.setFillColor(JC.ink.copy(alpha: 1)!); self.shadowLocal($0) }
    ctx.saveGState(); ctx.concatenate(f.squash)
    for (i, tl) in tails.enumerated() where tl.ready {
      outlined(ctx, tailPath(tl), fill: tl.color, line: 1.9)
      at(ctx, Item(xf: f.tailBells[i], alpha: 1)) { self.bell($0, .zero, r: 4.2, rot: 0) }
    }
    for (i, l) in f.legs.enumerated() { limb(ctx, l); at(ctx, Item(xf: f.shoes[i], alpha: 1)) { self.shoeLocal($0) } }
    at(ctx, Item(xf: Tt, alpha: 1)) { self.torsoLocal($0) }
    for l in f.arms { limb(ctx, l) }
    for (i, x) in f.sleeves.enumerated() { at(ctx, Item(xf: x, alpha: 1)) { self.sleeveLocal($0, f.arms[i].color) } }
    at(ctx, Item(xf: Tt, alpha: 1)) { self.ruffLocal($0) }
    at(ctx, Item(xf: Th, alpha: 1)) { self.headBaseLocal($0); self.featuresLocal($0); self.capLocal($0) }
    at(ctx, f.marotte) { self.marotteLocal($0) }
    at(ctx, f.orb) { self.orbLocal($0) }
    at(ctx, f.swirl) { self.swirlLocal($0) }
    at(ctx, f.scroll) { self.scrollLocal($0, self.pose.writeProgress) }
    at(ctx, f.quill) { self.quillLocal($0) }
    at(ctx, f.card) { self.cardLocal($0, front: f.cardFront) }
    for (i, b) in f.balls.enumerated() { at(ctx, b) { self.ballLocal($0, [JC.crimson, JC.amber, JC.red][i]) } }
    for h in f.hands { at(ctx, Item(xf: h, alpha: 1)) { self.handLocal($0) } }
    at(ctx, f.alert) { self.alertLocal($0) }
    for d in f.dots { at(ctx, d) { self.dotLocal($0) } }
    at(ctx, f.sweat) { self.sweatLocal($0) }
    for s in f.stars { at(ctx, s) { self.star($0, .zero, r: 4.2, rot: 0, fill: JC.gold) } }
    ctx.restoreGState()
    for q in particles { let (it, front) = particleItem(q); at(ctx, it) { self.particleLocal($0, q.kind, q.color, front: front) } }
    ctx.restoreGState()
  }

  private func at(_ ctx: CGContext, _ it: Item, _ f: (CGContext) -> Void) {
    guard it.alpha > 0.02 else { return }
    ctx.saveGState(); ctx.concatenate(it.xf); if it.alpha < 1 { ctx.setAlpha(it.alpha) }
    f(ctx); ctx.restoreGState()
  }

  // MARK: local drawing primitives (also baked into sprites by JesterLayers)
  fileprivate func outlined(_ ctx: CGContext, _ path: CGPath, fill: CGColor, line: CGFloat = 2.1) {
    ctx.addPath(path); ctx.setFillColor(fill); ctx.fillPath()
    ctx.addPath(path); ctx.setStrokeColor(JC.ink); ctx.setLineWidth(line); ctx.strokePath()
  }
  private func limb(_ ctx: CGContext, _ l: Limb) {
    let path = CGMutablePath(); path.move(to: l.a); path.addLine(to: l.j); path.addLine(to: l.b)
    ctx.addPath(path); ctx.setStrokeColor(JC.ink); ctx.setLineWidth(l.w + 4.2); ctx.strokePath()
    ctx.addPath(path); ctx.setStrokeColor(l.color); ctx.setLineWidth(l.w); ctx.strokePath()
    ctx.addPath(path); ctx.setStrokeColor(rgb(255, 255, 255, 0.18)); ctx.setLineWidth(l.w * 0.3); ctx.strokePath()
  }
  /// one bone as a capsule from (0,0) to (L,0); the ink pass and the colour pass are separate sprites so joints show no seam
  fileprivate func capsuleLocal(_ ctx: CGContext, _ L: CGFloat, _ w: CGFloat, ink: Bool, _ c: CGColor) {
    ctx.setLineCap(.round)
    ctx.move(to: .zero); ctx.addLine(to: P(L, 0))
    if ink { ctx.setStrokeColor(JC.ink); ctx.setLineWidth(w + 4.2); ctx.strokePath(); return }
    ctx.setStrokeColor(c); ctx.setLineWidth(w); ctx.strokePath()
    ctx.move(to: .zero); ctx.addLine(to: P(L, 0)); ctx.setStrokeColor(rgb(255, 255, 255, 0.18)); ctx.setLineWidth(w * 0.3); ctx.strokePath()
  }
  fileprivate func shadowLocal(_ ctx: CGContext) { ctx.setFillColor(rgb(0, 0, 0, 1)); ctx.fillEllipse(in: CGRect(x: -26, y: -3.5, width: 52, height: 7)) }
  fileprivate func sleeveLocal(_ ctx: CGContext, _ col: CGColor) {
    outlined(ctx, CGPath(ellipseIn: CGRect(x: -6, y: -5.5, width: 12, height: 11), transform: nil), fill: col, line: 1.8)
  }
  fileprivate func handLocal(_ ctx: CGContext) {
    outlined(ctx, CGPath(ellipseIn: CGRect(x: -4.8, y: -4.8, width: 9.6, height: 9.6), transform: nil), fill: JC.cream, line: 1.8)
  }
  /// left shoe, sole at the origin, curled toe pointing to -x (the right shoe is mirrored by its transform)
  fileprivate func shoeLocal(_ ctx: CGContext) {
    let s = CGMutablePath()
    s.move(to: P(6, 0)); s.addLine(to: P(-8, 0))
    s.addQuadCurve(to: P(-16, 8), control: P(-15, 0))
    s.addQuadCurve(to: P(-10, 5.5), control: P(-13, 4))
    s.addQuadCurve(to: P(-3, 7), control: P(-6, 7.5))
    s.addLine(to: P(5, 7)); s.addQuadCurve(to: P(6, 0), control: P(8, 3.5)); s.closeSubpath()
    outlined(ctx, s, fill: JC.crimsonDark, line: 1.8)
    bell(ctx, P(-16.5, 9), r: 2.4, rot: 0)
  }
  fileprivate func bell(_ ctx: CGContext, _ c: CGPoint, r: CGFloat, rot: CGFloat) {
    let path = CGPath(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r), transform: nil)
    outlined(ctx, path, fill: JC.gold, line: max(1, r * 0.35))
    ctx.setFillColor(rgb(255, 245, 210, 0.9))
    ctx.fillEllipse(in: CGRect(x: c.x - r * 0.55, y: c.y + r * 0.1, width: r * 0.6, height: r * 0.5))
    ctx.setStrokeColor(JC.goldDark); ctx.setLineWidth(max(0.7, r * 0.22))
    ctx.move(to: c + P(-r * 0.5 * cos(rot), -r * 0.5 * sin(rot)) - P(0, r * 0.25))
    ctx.addLine(to: c + P(r * 0.5 * cos(rot), r * 0.5 * sin(rot)) - P(0, r * 0.25)); ctx.strokePath()
  }
  fileprivate func torsoLocal(_ ctx: CGContext) {
    let body = CGMutablePath()
    body.move(to: P(-16, -3))
    var x: CGFloat = -16
    while x < 16 { body.addLine(to: P(x + 4, -10)); body.addLine(to: P(x + 8, -3)); x += 8 }
    body.addQuadCurve(to: P(13, 32), control: P(22, 13))
    body.addQuadCurve(to: P(-13, 32), control: P(0, 37))
    body.addQuadCurve(to: P(-16, -3), control: P(-22, 13))
    body.closeSubpath()
    ctx.addPath(body); ctx.setFillColor(JC.crimson); ctx.fillPath()
    ctx.saveGState()
    ctx.addPath(body); ctx.clip()
    ctx.setFillColor(JC.amber)
    for i in 0..<5 {
      for j in 0..<7 {
        let c = P(-30 + 10 * CGFloat(j) + (i % 2 == 0 ? 0 : 5), -10 + 11 * CGFloat(i))
        let d = CGMutablePath()
        d.move(to: c + P(0, 5.5)); d.addLine(to: c + P(5, 0)); d.addLine(to: c + P(0, -5.5)); d.addLine(to: c + P(-5, 0)); d.closeSubpath()
        ctx.addPath(d)
      }
    }
    ctx.fillPath()
    if let g = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB),
                          colors: [rgb(255, 255, 255, 0.16), rgb(0, 0, 0, 0), rgb(40, 0, 10, 0.22)] as CFArray, locations: [0, 0.45, 1]) {
      ctx.drawLinearGradient(g, start: P(-20, 0), end: P(20, 0), options: [])
    }
    ctx.setFillColor(JC.goldDark); ctx.fill(CGRect(x: -24, y: 4.5, width: 48, height: 4.5))
    ctx.setFillColor(JC.gold); ctx.fill(CGRect(x: -24, y: 6, width: 48, height: 2))
    ctx.restoreGState()
    ctx.addPath(body); ctx.setStrokeColor(JC.ink); ctx.setLineWidth(2.2); ctx.strokePath()
    outlined(ctx, CGPath(roundedRect: CGRect(x: -3.5, y: 3.5, width: 7, height: 6.5), cornerWidth: 1.5, cornerHeight: 1.5, transform: nil),
             fill: JC.gold, line: 1.4)
    for bx in stride(from: CGFloat(-12), through: 12, by: 8) { bell(ctx, P(bx, -11.5), r: 2.3, rot: 0) }
  }
  fileprivate func ruffLocal(_ ctx: CGContext) {
    for row in 0..<2 {
      for i in 0..<7 {
        let u = CGFloat(i) / 6, x = -15 + 30 * u
        let y = 34.5 - 2.2 * sin(.pi * u) + (row == 0 ? 2.2 : 0)
        let r: CGFloat = row == 0 ? 4.6 : 5
        outlined(ctx, CGPath(ellipseIn: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r), transform: nil),
                 fill: row == 0 ? JC.blush : JC.cream, line: 1.5)
      }
    }
  }
  fileprivate func tailPath(_ tl: Tail) -> CGPath {
    let c = tl.mid * 2 - (tl.base + tl.tip) * 0.5
    let n = 14
    var left: [CGPoint] = [], right: [CGPoint] = []
    for k in 0...n {
      let t = CGFloat(k) / CGFloat(n)
      let pt = bez(tl.base, c, tl.tip, t)
      let tan = norm(bez(tl.base, c, tl.tip, min(1, t + 0.02)) - bez(tl.base, c, tl.tip, max(0, t - 0.02)))
      let nrm = P(-tan.y, tan.x), w = mixf(5, 1.4, t)
      left.append(pt + nrm * w); right.append(pt - nrm * w)
    }
    let path = CGMutablePath()
    path.addLines(between: left + right.reversed()); path.closeSubpath()
    return path
  }
  /// face, cheeks and nose (head space, origin = face centre)
  fileprivate func headBaseLocal(_ ctx: CGContext) {
    let face = CGPath(ellipseIn: CGRect(x: -17, y: -16, width: 34, height: 32), transform: nil)
    outlined(ctx, face, fill: JC.skin, line: 2.2)
    ctx.saveGState(); ctx.addPath(face); ctx.clip()
    ctx.setFillColor(rgb(225, 150, 140, 0.25)); ctx.fillEllipse(in: CGRect(x: -20, y: -24, width: 40, height: 18))
    ctx.restoreGState()
    ctx.setFillColor(rgb(232, 130, 140, 0.55))
    ctx.fillEllipse(in: CGRect(x: -14, y: -7.5, width: 7.5, height: 5.5)); ctx.fillEllipse(in: CGRect(x: 6.5, y: -7.5, width: 7.5, height: 5.5))
    outlined(ctx, CGPath(ellipseIn: CGRect(x: -2.6, y: -4.6, width: 5.2, height: 5.2), transform: nil), fill: JC.red, line: 1.2)
    ctx.setFillColor(rgb(255, 255, 255, 0.7)); ctx.fillEllipse(in: CGRect(x: -1.4, y: -2.4, width: 1.6, height: 1.4))
  }
  /// eyes, brows, mouth, tongue: the only part of the head that changes every frame
  fileprivate func featuresLocal(_ ctx: CGContext) {
    let p = pose
    for side in [CGFloat(-1), 1] {
      let c = P(6.2 * side, 1.5)
      if p.dizzy > 0.5 {
        let sp = CGMutablePath(); let spin = CGFloat(now) * 7 * side
        for k in 0...24 { let a = CGFloat(k) * 0.55 + spin, r = CGFloat(k) * 0.17; let q = c + P(cos(a), sin(a)) * r; k == 0 ? sp.move(to: q) : sp.addLine(to: q) }
        ctx.addPath(sp); ctx.setStrokeColor(JC.ink); ctx.setLineWidth(1.3); ctx.strokePath()
      } else if p.happy > 0.5 {
        ctx.move(to: c + P(-3.4, -1)); ctx.addQuadCurve(to: c + P(3.4, -1), control: c + P(0, 4.5))
        ctx.setStrokeColor(JC.ink); ctx.setLineWidth(1.9); ctx.strokePath()
      } else if p.eyeOpen < 0.15 {
        ctx.move(to: c + P(-3.6, 0.5)); ctx.addQuadCurve(to: c + P(3.6, 0.5), control: c + P(0, -2.8))
        ctx.setStrokeColor(JC.ink); ctx.setLineWidth(1.7); ctx.strokePath()
      } else {
        let h = 5 * p.eyeOpen
        let eye = CGPath(ellipseIn: CGRect(x: c.x - 4.1, y: c.y - h, width: 8.2, height: 2 * h), transform: nil)
        outlined(ctx, eye, fill: JC.white, line: 1.4)
        ctx.saveGState(); ctx.addPath(eye); ctx.clip()
        let pc = c + P(p.look.x * 1.7, p.look.y * 2 * min(1, p.eyeOpen))
        ctx.setFillColor(JC.ink); ctx.fillEllipse(in: CGRect(x: pc.x - 2.6, y: pc.y - 2.6, width: 5.2, height: 5.2))
        ctx.setFillColor(JC.white); ctx.fillEllipse(in: CGRect(x: pc.x + 0.2, y: pc.y + 0.5, width: 1.8, height: 1.8))
        ctx.restoreGState()
      }
      let b = p.brow, inner = c + P(-2 * side, 7 + 1.6 * b + (b < 0 ? -b * 2.2 : 0)), outer = c + P(4 * side, 7 + 1.6 * b + (b < 0 ? b * 0.8 : 0))
      ctx.move(to: inner); ctx.addLine(to: outer); ctx.setStrokeColor(JC.ink); ctx.setLineWidth(1.6); ctx.strokePath()
    }
    let m = P(0, -8.5), s = p.smile, o = clamp(p.mouthOpen)
    if p.dizzy > 0.5 {
      let w = CGMutablePath(); w.move(to: m + P(-5, 0))
      for k in 1...8 { w.addLine(to: m + P(-5 + CGFloat(k) * 1.25, (k % 2 == 0 ? 0 : 1.3))) }
      ctx.addPath(w); ctx.setStrokeColor(JC.ink); ctx.setLineWidth(1.4); ctx.strokePath()
    } else if o < 0.12 {
      ctx.move(to: m + P(-5, 0.8 * s)); ctx.addQuadCurve(to: m + P(5, 0.8 * s), control: m + P(0, -4.5 * s))
      ctx.setStrokeColor(JC.ink); ctx.setLineWidth(1.7); ctx.strokePath()
    } else {
      let wdt = 5 + 1.2 * o
      let mp = CGMutablePath(); mp.move(to: m + P(-wdt, 1.2 * s))
      mp.addQuadCurve(to: m + P(wdt, 1.2 * s), control: m + P(0, -1.5 * s))
      mp.addQuadCurve(to: m + P(-wdt, 1.2 * s), control: m + P(0, -2 * s - 11 * o))
      mp.closeSubpath()
      ctx.addPath(mp); ctx.setFillColor(JC.mouth); ctx.fillPath()
      ctx.saveGState(); ctx.addPath(mp); ctx.clip()
      ctx.setFillColor(JC.rose); ctx.fillEllipse(in: CGRect(x: m.x - 3.5, y: m.y - 2 * s - 9 * o, width: 7, height: 5))
      ctx.restoreGState()
      ctx.addPath(mp); ctx.setStrokeColor(JC.ink); ctx.setLineWidth(1.5); ctx.strokePath()
    }
    if p.tongue > 0.5 && o < 0.3 {
      outlined(ctx, CGPath(ellipseIn: CGRect(x: m.x + 2, y: m.y - 3.2, width: 3.6, height: 3.6), transform: nil), fill: JC.rose, line: 1.1)
    }
  }
  /// change signature of the features, so the GPU renderer re-rasterises them only when the face actually changed
  fileprivate var featuresKey: [Int] {
    let p = pose
    return [p.eyeOpen, p.happy > 0.5 ? 1 : 0, p.mouthOpen, p.smile, p.brow, p.tongue > 0.5 ? 1 : 0, p.look.x, p.look.y].map { Int(($0 * 40).rounded()) }
      + [p.dizzy > 0.5 ? Int(now * 40) : 0]
  }
  fileprivate func capLocal(_ ctx: CGContext) {
    let cap = CGMutablePath()
    cap.move(to: P(-18, 7)); cap.addCurve(to: P(18, 7), control1: P(-19, 25), control2: P(19, 25)); cap.closeSubpath()
    ctx.saveGState(); ctx.addPath(cap); ctx.clip()
    ctx.setFillColor(JC.crimson); ctx.fill(CGRect(x: -20, y: 0, width: 20, height: 30))
    ctx.setFillColor(JC.amber); ctx.fill(CGRect(x: 0, y: 0, width: 20, height: 30))
    ctx.restoreGState()
    ctx.addPath(cap); ctx.setStrokeColor(JC.ink); ctx.setLineWidth(2.1); ctx.strokePath()
    let band = CGMutablePath()
    band.move(to: P(-19, 5)); band.addQuadCurve(to: P(19, 5), control: P(0, 2))
    band.addLine(to: P(18.5, 10.5)); band.addQuadCurve(to: P(-18.5, 10.5), control: P(0, 8)); band.closeSubpath()
    outlined(ctx, band, fill: JC.crimsonDark, line: 1.8)
    for bx in [CGFloat(-11), 0, 11] {
      let d = CGMutablePath(); let c = P(bx, 7 - (bx == 0 ? 1.2 : 0.5))
      d.move(to: c + P(0, 2.4)); d.addLine(to: c + P(2, 0)); d.addLine(to: c + P(0, -2.4)); d.addLine(to: c + P(-2, 0)); d.closeSubpath()
      ctx.addPath(d); ctx.setFillColor(JC.gold); ctx.fillPath()
    }
  }
  /// scepter with a mini jester head; origin = bottom of the stick, pointing up
  fileprivate func marotteLocal(_ ctx: CGContext) {
    let L: CGFloat = 46, top = P(0, L)
    ctx.move(to: .zero); ctx.addLine(to: top); ctx.setStrokeColor(JC.ink); ctx.setLineWidth(5.6); ctx.strokePath()
    ctx.move(to: .zero); ctx.addLine(to: top); ctx.setStrokeColor(JC.wood); ctx.setLineWidth(2.8); ctx.strokePath()
    bell(ctx, .zero, r: 2.4, rot: 0)
    let hc = top + P(0, 5)
    for s in [CGFloat(-1), 1] {
      let tip = hc + P(-sin(s * 0.9), cos(s * 0.9)) * 11
      let tp = CGMutablePath(); tp.move(to: hc + P(-3 * s, 2)); tp.addQuadCurve(to: tip, control: hc + P(-6 * s, 8)); tp.addLine(to: hc + P(2 * s, 3)); tp.closeSubpath()
      outlined(ctx, tp, fill: s < 0 ? JC.crimson : JC.amber, line: 1.3)
      bell(ctx, tip, r: 1.9, rot: 0)
    }
    outlined(ctx, CGPath(ellipseIn: CGRect(x: hc.x - 5, y: hc.y - 5, width: 10, height: 10), transform: nil), fill: JC.skin, line: 1.4)
    ctx.setFillColor(JC.ink)
    ctx.fillEllipse(in: CGRect(x: hc.x - 2.6, y: hc.y, width: 1.4, height: 1.6)); ctx.fillEllipse(in: CGRect(x: hc.x + 1.2, y: hc.y, width: 1.4, height: 1.6))
    ctx.setFillColor(JC.red); ctx.fillEllipse(in: CGRect(x: hc.x - 1, y: hc.y - 2, width: 2, height: 2))
  }
  fileprivate func orbLocal(_ ctx: CGContext) {
    let r = CGRect(x: -8.5, y: -8.5, width: 17, height: 17)
    if let g = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB),
                          colors: [rgb(255, 210, 220, 0.95), rgb(232, 130, 140, 0.9), rgb(150, 30, 60, 0.85)] as CFArray, locations: [0, 0.5, 1]) {
      ctx.saveGState(); ctx.addEllipse(in: r); ctx.clip()
      ctx.drawRadialGradient(g, startCenter: P(-2.5, 3), startRadius: 0, endCenter: .zero, endRadius: 8.5, options: [])
      ctx.restoreGState()
    }
    ctx.addEllipse(in: r); ctx.setStrokeColor(JC.ink); ctx.setLineWidth(1.6); ctx.strokePath()
    ctx.setFillColor(rgb(255, 255, 255, 0.85)); ctx.fillEllipse(in: CGRect(x: -5, y: 2.5, width: 3.5, height: 2.5))
  }
  fileprivate func swirlLocal(_ ctx: CGContext) {
    for k in 0..<2 {
      let a0 = CGFloat(k) * 2.6
      ctx.addArc(center: .zero, radius: 4 + CGFloat(k) * 2.2, startAngle: a0, endAngle: a0 + 2.2, clockwise: false)
      ctx.setStrokeColor(rgb(255, 240, 200, 0.7)); ctx.setLineWidth(1.3); ctx.strokePath()
    }
  }
  fileprivate func scrollLocal(_ ctx: CGContext, _ progress: CGFloat) {
    let r = CGRect(x: -15, y: -11, width: 30, height: 22)
    outlined(ctx, CGPath(rect: r, transform: nil), fill: JC.cream, line: 1.6)
    for k in 0..<4 {
      let prog = clamp(progress - CGFloat(k))
      guard prog > 0 else { continue }
      let y = r.maxY - 5 - CGFloat(k) * 4.2, x0 = r.minX + 3, x1 = x0 + 24 * prog
      let lp = CGMutablePath(); lp.move(to: P(x0, y))
      var x = x0; var up = true
      while x < x1 { x = min(x1, x + 1.6); lp.addLine(to: P(x, y + (up ? 0.7 : -0.5))); up.toggle() }
      ctx.addPath(lp); ctx.setStrokeColor(rgb(60, 30, 40, 0.8)); ctx.setLineWidth(0.9); ctx.strokePath()
    }
    for ry in [r.maxY, r.minY] {
      outlined(ctx, CGPath(roundedRect: CGRect(x: r.minX - 2.5, y: ry - 2.3, width: r.width + 5, height: 4.6), cornerWidth: 2.3, cornerHeight: 2.3, transform: nil),
               fill: JC.amberDark, line: 1.4)
    }
  }
  fileprivate func quillLocal(_ ctx: CGContext) {
    let f = CGMutablePath()
    f.move(to: P(0, -4)); f.addQuadCurve(to: P(1, 24), control: P(-9, 10)); f.addQuadCurve(to: P(0, -4), control: P(8, 12)); f.closeSubpath()
    outlined(ctx, f, fill: JC.white, line: 1.3)
    ctx.move(to: P(0, -6)); ctx.addLine(to: P(0.8, 22)); ctx.setStrokeColor(JC.rose); ctx.setLineWidth(1); ctx.strokePath()
    ctx.move(to: P(0, -4)); ctx.addLine(to: P(0, -8)); ctx.setStrokeColor(JC.ink); ctx.setLineWidth(1.6); ctx.strokePath()
  }
  fileprivate func cardLocal(_ ctx: CGContext, front: Bool) {
    outlined(ctx, CGPath(roundedRect: CGRect(x: -5, y: -7, width: 10, height: 14), cornerWidth: 1.6, cornerHeight: 1.6, transform: nil),
             fill: front ? JC.white : JC.crimson, line: 1.2)
    if front {
      let d = CGMutablePath(); d.move(to: P(0, 3.5)); d.addLine(to: P(2.6, 0)); d.addLine(to: P(0, -3.5)); d.addLine(to: P(-2.6, 0)); d.closeSubpath()
      ctx.addPath(d); ctx.setFillColor(JC.crimson); ctx.fillPath()
    }
  }
  fileprivate func ballLocal(_ ctx: CGContext, _ col: CGColor) {
    outlined(ctx, CGPath(ellipseIn: CGRect(x: -4.6, y: -4.6, width: 9.2, height: 9.2), transform: nil), fill: col, line: 1.6)
    ctx.setFillColor(rgb(255, 255, 255, 0.6)); ctx.fillEllipse(in: CGRect(x: -2.6, y: 0.6, width: 2.6, height: 2))
  }
  fileprivate func alertLocal(_ ctx: CGContext) {
    outlined(ctx, CGPath(roundedRect: CGRect(x: -2.6, y: 0, width: 5.2, height: 13), cornerWidth: 2.6, cornerHeight: 2.6, transform: nil), fill: JC.red, line: 1.6)
    outlined(ctx, CGPath(ellipseIn: CGRect(x: -2.8, y: -7, width: 5.6, height: 5.6), transform: nil), fill: JC.red, line: 1.6)
  }
  fileprivate func dotLocal(_ ctx: CGContext) {
    outlined(ctx, CGPath(ellipseIn: CGRect(x: -4.5, y: -4.5, width: 9, height: 9), transform: nil), fill: JC.white, line: 1.1)
  }
  fileprivate func sweatLocal(_ ctx: CGContext) {
    let d = CGMutablePath(); d.move(to: P(0, 5)); d.addQuadCurve(to: P(0, -2.5), control: P(4.5, -2)); d.addQuadCurve(to: P(0, 5), control: P(-4.5, -2))
    outlined(ctx, d, fill: JC.sweat, line: 1.2)
  }
  fileprivate func star(_ ctx: CGContext, _ c: CGPoint, r: CGFloat, rot: CGFloat, fill: CGColor, points: Int = 5) {
    let s = CGMutablePath()
    for k in 0..<(points * 2) {
      let a = rot + CGFloat(k) * .pi / CGFloat(points) + .pi / 2, rr = k % 2 == 0 ? r : r * 0.45
      let q = c + P(cos(a), sin(a)) * rr
      k == 0 ? s.move(to: q) : s.addLine(to: q)
    }
    s.closeSubpath()
    outlined(ctx, s, fill: fill, line: 1.1)
  }
}

private func TR(_ p: CGPoint) -> CGAffineTransform { CGAffineTransform(translationX: p.x, y: p.y) }

// MARK: GPU renderer
/// Cutout renderer: every rigid part is rasterised once into a sprite; per frame only layer transforms change, so the
/// window server composites on the GPU. Only the face features and the scroll text are re-rasterised (when they change),
/// and the three hat tails are CAShapeLayers.
final class JesterLayers {
  let root = CALayer()
  var builtFor = ""
  private let world = CALayer(), body = CALayer(), particleRoot = CALayer()
  private let glow = CAGradientLayer()
  private var shadow = CALayer(), tailShapes: [CAShapeLayer] = [], tailBells: [CALayer] = []
  private var legs: [[CALayer]] = [], shoes: [CALayer] = [], torso = CALayer(), arms: [[CALayer]] = [], sleeves: [CALayer] = []
  private var ruff = CALayer(), headBase = CALayer(), features = CALayer(), cap = CALayer()
  private var marotte = CALayer(), orb = CALayer(), swirl = CALayer(), scroll = CALayer(), quill = CALayer(), card = CALayer()
  private var balls: [CALayer] = [], hands: [CALayer] = [], alert = CALayer(), dots: [CALayer] = [], sweat = CALayer(), stars: [CALayer] = []
  private var pool: [CALayer] = [], poolKind: [String] = []
  private var cardImgs: [CGImage?] = [nil, nil]
  private var spriteCache: [String: CGImage] = [:]
  private var R: CGFloat = 2.4
  private var featureCtx: CGContext?, scrollCtx: CGContext?
  private var lastFeatures: [Int] = [], lastScroll: CGFloat = -1
  private let featureRect = CGRect(x: -15, y: -22, width: 30, height: 37), scrollRect = CGRect(x: -19, y: -15, width: 38, height: 30)

  private func bake(_ rect: CGRect, _ draw: (CGContext) -> Void) -> CGImage? {
    let w = max(1, Int(ceil(rect.width * R))), h = max(1, Int(ceil(rect.height * R)))
    guard let c = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
    c.scaleBy(x: R, y: R); c.translateBy(x: -rect.minX, y: -rect.minY)
    c.setLineJoin(.round); c.setLineCap(.round)
    draw(c)
    return c.makeImage()
  }
  /// layer whose local origin (the sprite's drawing origin) sits at its anchor, so setAffineTransform(xf) maps local -> parent
  private func place(_ l: CALayer, _ rect: CGRect) {
    l.bounds = CGRect(origin: .zero, size: rect.size)
    l.anchorPoint = CGPoint(x: -rect.minX / rect.width, y: -rect.minY / rect.height)
    l.position = .zero; l.contentsGravity = .resize
  }
  private func sprite(_ rect: CGRect, into parent: CALayer, _ draw: (CGContext) -> Void) -> CALayer {
    let l = CALayer(); place(l, rect); l.contents = bake(rect, draw); parent.addSublayer(l); return l
  }

  func build(_ rig: JesterRig, size: CGSize, backing: CGFloat) {
    CATransaction.begin(); CATransaction.setDisableActions(true)
    R = rig.zoom * backing * 1.5
    for l in [root, world, body, particleRoot] { l.anchorPoint = .zero; l.position = .zero }
    root.bounds = CGRect(origin: .zero, size: size); world.bounds = root.bounds
    body.bounds = .zero; particleRoot.bounds = .zero
    world.sublayerTransform = CATransform3DMakeAffineTransform(
      CGAffineTransform(translationX: size.width / 2, y: 10 + 12 * rig.zoom).scaledBy(x: rig.zoom, y: rig.zoom))
    root.addSublayer(world)
    glow.type = .radial; glow.startPoint = CGPoint(x: 0.5, y: 0.5); glow.endPoint = CGPoint(x: 1, y: 1)
    glow.bounds = CGRect(x: 0, y: 0, width: 190, height: 190)
    glow.colors = [rig.accent.copy(alpha: 0.32)!, rig.accent.copy(alpha: 0)!]
    world.addSublayer(glow)
    shadow = sprite(CGRect(x: -27, y: -4.5, width: 54, height: 9), into: world) { rig.shadowLocal($0) }
    world.addSublayer(body); world.addSublayer(particleRoot)

    let bellRect = CGRect(x: -6, y: -6, width: 12, height: 12)
    for tl in rig.tails {
      let s = CAShapeLayer(); s.fillColor = tl.color; s.strokeColor = JC.ink; s.lineWidth = 1.9; s.lineJoin = .round
      body.addSublayer(s); tailShapes.append(s)
      tailBells.append(sprite(bellRect, into: body) { rig.bell($0, .zero, r: 4.2, rot: 0) })
    }
    func capsule(_ L: CGFloat, _ w: CGFloat, _ ink: Bool, _ c: CGColor) -> CALayer {
      sprite(CGRect(x: -w / 2 - 3, y: -w / 2 - 3, width: L + w + 6, height: w + 6), into: body) { rig.capsuleLocal($0, L, w, ink: ink, c) }
    }
    for col in [JC.amber, JC.crimson] {
      legs.append([capsule(20, 7.5, true, col), capsule(21, 7.5, true, col), capsule(20, 7.5, false, col), capsule(21, 7.5, false, col)])
      shoes.append(sprite(CGRect(x: -20, y: -3, width: 30, height: 16), into: body) { rig.shoeLocal($0) })
    }
    torso = sprite(CGRect(x: -25, y: -16, width: 50, height: 56), into: body) { rig.torsoLocal($0) }
    for col in [JC.crimson, JC.amber] {
      arms.append([capsule(15.5, 6.5, true, col), capsule(15.5, 6.5, true, col), capsule(15.5, 6.5, false, col), capsule(15.5, 6.5, false, col)])
    }
    for col in [JC.crimson, JC.amber] { sleeves.append(sprite(CGRect(x: -8, y: -8, width: 16, height: 16), into: body) { rig.sleeveLocal($0, col) }) }
    ruff = sprite(CGRect(x: -22, y: 27, width: 44, height: 16), into: body) { rig.ruffLocal($0) }
    headBase = sprite(CGRect(x: -20, y: -19, width: 40, height: 38), into: body) { rig.headBaseLocal($0) }
    features = CALayer(); place(features, featureRect); body.addSublayer(features)
    cap = sprite(CGRect(x: -21, y: 0, width: 42, height: 26), into: body) { rig.capLocal($0) }
    marotte = sprite(CGRect(x: -17, y: -5, width: 34, height: 72), into: body) { rig.marotteLocal($0) }
    orb = sprite(CGRect(x: -11, y: -11, width: 22, height: 22), into: body) { rig.orbLocal($0) }
    swirl = sprite(CGRect(x: -11, y: -11, width: 22, height: 22), into: body) { rig.swirlLocal($0) }
    scroll = CALayer(); place(scroll, scrollRect); body.addSublayer(scroll)
    quill = sprite(CGRect(x: -11, y: -10, width: 22, height: 36), into: body) { rig.quillLocal($0) }
    let cardRect = CGRect(x: -7, y: -9, width: 14, height: 18)
    cardImgs = [bake(cardRect) { rig.cardLocal($0, front: true) }, bake(cardRect) { rig.cardLocal($0, front: false) }]
    card = CALayer(); place(card, cardRect); body.addSublayer(card)
    for col in [JC.crimson, JC.amber, JC.red] { balls.append(sprite(CGRect(x: -6.5, y: -6.5, width: 13, height: 13), into: body) { rig.ballLocal($0, col) }) }
    for _ in 0..<2 { hands.append(sprite(CGRect(x: -7, y: -7, width: 14, height: 14), into: body) { rig.handLocal($0) }) }
    alert = sprite(CGRect(x: -5, y: -10, width: 10, height: 26), into: body) { rig.alertLocal($0) }
    for _ in 0..<3 { dots.append(sprite(CGRect(x: -6, y: -6, width: 12, height: 12), into: body) { rig.dotLocal($0) }) }
    sweat = sprite(CGRect(x: -6, y: -5, width: 12, height: 12), into: body) { rig.sweatLocal($0) }
    for _ in 0..<3 { stars.append(sprite(CGRect(x: -6, y: -6, width: 12, height: 12), into: body) { rig.star($0, .zero, r: 4.2, rot: 0, fill: JC.gold) }) }
    CATransaction.commit()
  }

  private func set(_ l: CALayer, _ it: JesterRig.Item) {
    let vis = it.alpha > 0.02
    if l.isHidden == vis { l.isHidden = !vis }
    guard vis else { return }
    l.setAffineTransform(it.xf)
    let o = Float(min(1, it.alpha)); if l.opacity != o { l.opacity = o }
  }
  private func setLimb(_ ls: [CALayer], _ l: JesterRig.Limb) {
    let t1 = TR(l.a).rotated(by: atan2(l.j.y - l.a.y, l.j.x - l.a.x)), t2 = TR(l.j).rotated(by: atan2(l.b.y - l.j.y, l.b.x - l.j.x))
    ls[0].setAffineTransform(t1); ls[1].setAffineTransform(t2); ls[2].setAffineTransform(t1); ls[3].setAffineTransform(t2)
  }
  private func redraw(_ ctx: inout CGContext?, _ l: CALayer, _ rect: CGRect, _ draw: (CGContext) -> Void) {
    if ctx == nil {
      ctx = CGContext(data: nil, width: Int(ceil(rect.width * R)), height: Int(ceil(rect.height * R)), bitsPerComponent: 8, bytesPerRow: 0,
                      space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
      ctx?.scaleBy(x: R, y: R); ctx?.translateBy(x: -rect.minX, y: -rect.minY); ctx?.setLineJoin(.round); ctx?.setLineCap(.round)
    }
    guard let c = ctx else { return }
    c.clear(rect); draw(c); l.contents = c.makeImage()
  }

  func update(_ rig: JesterRig) {
    let f = rig.frame()
    CATransaction.begin(); CATransaction.setDisableActions(true)
    glow.position = rig.glowCenter; glow.opacity = Float(rig.glowAlpha)
    set(shadow, f.shadow)
    body.setAffineTransform(f.squash)
    for i in 0..<tailShapes.count where rig.tails[i].ready {
      tailShapes[i].path = rig.tailPath(rig.tails[i]); tailBells[i].setAffineTransform(f.tailBells[i])
    }
    for i in 0..<2 {
      setLimb(legs[i], f.legs[i]); shoes[i].setAffineTransform(f.shoes[i])
      setLimb(arms[i], f.arms[i]); sleeves[i].setAffineTransform(f.sleeves[i]); hands[i].setAffineTransform(f.hands[i])
    }
    torso.setAffineTransform(rig.Tt); ruff.setAffineTransform(rig.Tt)
    headBase.setAffineTransform(rig.Th); features.setAffineTransform(rig.Th); cap.setAffineTransform(rig.Th)
    let fk = rig.featuresKey
    if fk != lastFeatures { lastFeatures = fk; redraw(&featureCtx, features, featureRect) { rig.featuresLocal($0) } }
    set(marotte, f.marotte); set(orb, f.orb); set(swirl, f.swirl); set(quill, f.quill)
    set(scroll, f.scroll)
    let wp = (rig.pose.writeProgress * 20).rounded() / 20
    if f.scroll.alpha > 0.02 && wp != lastScroll { lastScroll = wp; redraw(&scrollCtx, scroll, scrollRect) { rig.scrollLocal($0, wp) } }
    set(card, f.card); card.contents = cardImgs[f.cardFront ? 0 : 1]
    for i in 0..<3 { set(balls[i], f.balls[i]); set(dots[i], f.dots[i]); set(stars[i], f.stars[i]) }
    set(alert, f.alert); set(sweat, f.sweat)

    // particles: a pool of layers, re-pointed at a cached sprite whenever a slot changes kind/colour
    let ps = rig.particles
    while pool.count < ps.count { let l = CALayer(); particleRoot.addSublayer(l); pool.append(l); poolKind.append("") }
    for (i, q) in ps.enumerated() {
      let l = pool[i], (it, front) = rig.particleItem(q)
      let key = "\(q.kind)|\(q.color.hashValue)|\(front)"
      if poolKind[i] != key {
        poolKind[i] = key
        if q.kind == .confetti {
          l.contents = nil; l.backgroundColor = q.color
          l.bounds = CGRect(x: 0, y: 0, width: 1, height: 0.6); l.anchorPoint = CGPoint(x: 0.5, y: 0.5); l.position = .zero
        } else {
          let rect: CGRect = {
            switch q.kind {
            case .sparkle: return CGRect(x: -7, y: -7, width: 14, height: 14)
            case .puff: return CGRect(x: -8, y: -8, width: 16, height: 16)
            case .ink: return CGRect(x: -3, y: -3, width: 6, height: 6)
            case .card: return CGRect(x: -7, y: -9, width: 14, height: 18)
            default: return CGRect(x: -3, y: -5, width: 18, height: 20)
            }
          }()
          if spriteCache[key] == nil { spriteCache[key] = bake(rect) { rig.particleLocal($0, q.kind, q.color, front: front) } }
          l.backgroundColor = nil; place(l, rect); l.contents = spriteCache[key]
        }
      }
      set(l, it)
    }
    for i in ps.count..<pool.count where !pool[i].isHidden { pool[i].isHidden = true }
    CATransaction.commit()
  }
}
