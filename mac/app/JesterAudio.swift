// JesterAudio.swift: procedural sound effects and "babble" for the jester. Everything is synthesised in code at
// launch (no audio files). The engine only runs while something is playing, then stops so it costs nothing idle.
import AVFoundation

final class JesterAudio {
  private let engine = AVAudioEngine()
  private let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1)!
  private var players: [AVAudioPlayerNode] = []
  private var nextPlayer = 0
  private var buffers: [String: AVAudioPCMBuffer] = [:]
  private var stopWork: DispatchWorkItem?
  private var lastPlayed: [String: Double] = [:]
  var volume: Float = 0.35
  private let sr: Float = 44100

  init() {
    for _ in 0..<6 {
      let p = AVAudioPlayerNode(); engine.attach(p); engine.connect(p, to: engine.mainMixerNode, format: format); players.append(p)
    }
    buffers["jingle"] = buffer(peak(jingle(count: 5, spread: 0.14, gain: 0.5), 0.6))
    buffers["tinkle"] = buffer(peak(jingle(count: 2, spread: 0.05, gain: 0.25), 0.3))
    buffers["boing"] = buffer(peak(boing(), 0.6))
    buffers["whoosh"] = buffer(peak(whoosh(0.42), 0.55))
    buffers["slide"] = buffer(peak(slide(), 0.45))
    buffers["thud"] = buffer(peak(thud(), 0.7))
    buffers["tada"] = buffer(peak(tada(), 0.6))
    buffers["flick"] = buffer(peak(flick(), 0.45))
    buffers["giggle"] = buffer(peak(giggle(), 0.5))
  }

  /// minGap stops the same sound machine-gunning (e.g. bells on every swing of the hat)
  func play(_ name: String, gain: Float = 1, minGap: Double = 0.12) {
    guard let b = buffers[name] else { return }
    let now = CACurrentMediaTime()
    if let last = lastPlayed[name], now - last < minGap { return }
    lastPlayed[name] = now
    schedule(b, gain: gain)
  }

  /// Animal-Crossing-style babble: one formant "syllable" per few letters, seeded by the text so a line always sounds the same.
  func babble(_ text: String) { if let s = babbleSamples(text), let b = buffer(s) { schedule(b, gain: 1) } }
  private func babbleSamples(_ text: String) -> [Float]? {
    let letters = text.unicodeScalars.filter { CharacterSet.letters.contains($0) }.count
    guard letters > 0 else { return nil }
    var rng = SeededRNG(seed: text.utf8.reduce(UInt64(14695981039346656037)) { ($0 ^ UInt64($1)) &* 1099511628211 })   // FNV-1a, stable across launches
    let vowels: [(Float, Float)] = [(730, 1090), (530, 1840), (300, 2290), (570, 840), (440, 1020)]
    var out: [Float] = []
    for i in 0..<min(22, max(3, letters / 3)) {
      let v = vowels[Int(rng.next() % UInt64(vowels.count))]
      let f0 = 420 + Float(rng.next() % 160) - (i % 5 == 4 ? 60 : 0)
      out += formant(f0: f0, f1: v.0, f2: v.1, dur: 0.07, drop: 0.06)
      out += [Float](repeating: 0, count: Int(0.022 * sr))
    }
    return peak(out, 0.4)
  }

  /// for tools/tests: the synthesised buffer of a named effect, or of a babble line
  func rendered(_ name: String) -> AVAudioPCMBuffer? { buffers[name] }
  func babbleBuffer(_ text: String) -> AVAudioPCMBuffer? { babbleSamples(text).flatMap { buffer($0) } }

  // MARK: playback
  private func schedule(_ b: AVAudioPCMBuffer, gain: Float) {
    if !engine.isRunning {
      engine.mainMixerNode.outputVolume = volume
      do { try engine.start() } catch { return }
    }
    engine.mainMixerNode.outputVolume = volume
    let p = players[nextPlayer]; nextPlayer = (nextPlayer + 1) % players.count
    p.volume = gain
    p.scheduleBuffer(b, at: nil, options: [.interrupts])
    if !p.isPlaying { p.play() }
    let secs = Double(b.frameLength) / Double(sr) + 4
    stopWork?.cancel()
    let w = DispatchWorkItem { [weak self] in self?.players.forEach { $0.stop() }; self?.engine.stop() }
    stopWork = w
    DispatchQueue.main.asyncAfter(deadline: .now() + secs, execute: w)
  }
  private func buffer(_ s: [Float]) -> AVAudioPCMBuffer? {
    guard let b = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(s.count)) else { return nil }
    b.frameLength = AVAudioFrameCount(s.count)
    let ch = b.floatChannelData![0]
    for i in 0..<s.count { ch[i] = max(-1, min(1, s[i])) }
    return b
  }

  // MARK: synthesis
  /// scale to a target peak so effects sit at consistent loudness and never clip
  private func peak(_ s: [Float], _ target: Float) -> [Float] {
    let m = s.reduce(0) { max($0, abs($1)) }
    return m > 0 ? s.map { $0 * target / m } : s
  }
  private func n(_ secs: Float) -> Int { Int(secs * sr) }
  private func bell(_ f: Float, dur: Float, gain: Float) -> [Float] {
    let partials: [(Float, Float, Float)] = [(1, 1, 7), (2.76, 0.5, 10), (5.4, 0.25, 15), (8.93, 0.12, 22)]
    return (0..<n(dur)).map { i in
      let t = Float(i) / sr, atk = min(1, t / 0.002)
      return atk * partials.reduce(0) { $0 + $1.1 * exp(-t * $1.2) * sin(2 * .pi * f * $1.0 * t) } * gain * 0.45
    }
  }
  private func mix(_ parts: [(Float, [Float])]) -> [Float] {
    let len = parts.map { n($0.0) + $0.1.count }.max() ?? 0
    var out = [Float](repeating: 0, count: len)
    for (at, s) in parts { let o = n(at); for i in 0..<s.count { out[o + i] += s[i] } }
    return out
  }
  private func jingle(count: Int, spread: Float, gain: Float) -> [Float] {
    mix((0..<count).map { k in (Float(k) * spread / Float(max(1, count - 1)) + Float.random(in: 0...0.015),
                                bell(Float.random(in: 2300...3600), dur: 0.45, gain: gain)) })
  }
  private func boing() -> [Float] {
    var ph: Float = 0
    return (0..<n(0.6)).map { i in
      let t = Float(i) / sr
      let f = (110 + 320 * exp(-7 * t)) * (1 + 0.18 * sin(2 * .pi * 26 * t) * exp(-3 * t))
      ph += 2 * .pi * f / sr
      return sin(ph) * exp(-3.2 * t) * min(1, t / 0.004) * 0.7
    }
  }
  private func whoosh(_ d: Float) -> [Float] {
    var lp: Float = 0
    return (0..<n(d)).map { i in
      let t = Float(i) / sr, u = t / d
      let a = 0.02 + 0.25 * sin(.pi * u)      // low-pass cutoff sweeps up then down
      lp += a * (Float.random(in: -1...1) - lp)
      return lp * sin(.pi * u) * 1.6
    }
  }
  private func slide() -> [Float] {
    var ph: Float = 0
    return (0..<n(0.6)).map { i in
      let t = Float(i) / sr, u = t / 0.6
      let f = (1600 - 1250 * u * u) * (1 + 0.012 * sin(2 * .pi * 7 * t))
      ph += 2 * .pi * f / sr
      return (sin(ph) + 0.2 * sin(2 * ph)) * min(1, t / 0.02) * (1 - u * 0.6) * 0.45
    }
  }
  private func thud() -> [Float] {
    var ph: Float = 0, lp: Float = 0
    return (0..<n(0.35)).map { i in
      let t = Float(i) / sr
      ph += 2 * .pi * (95 - 50 * t) / sr
      lp += 0.08 * (Float.random(in: -1...1) - lp)
      return (sin(ph) * exp(-9 * t) * 0.9 + lp * exp(-25 * t) * 1.4) * min(1, t / 0.002)
    }
  }
  private func tada() -> [Float] {
    let notes: [Float] = [1046.5, 1318.5, 1568, 2093]   // C6 E6 G6 C7
    return mix(notes.enumerated().map { (Float($0.offset) * 0.06, bell($0.element, dur: 0.9, gain: 0.55)) } + [(0.24, jingle(count: 4, spread: 0.1, gain: 0.3))])
  }
  private func flick() -> [Float] {
    var ph: Float = 0
    return (0..<n(0.06)).map { i in
      let t = Float(i) / sr
      ph += 2 * .pi * (900 + 18000 * t) / sr
      return sin(ph) * exp(-40 * t) * 0.5
    } + whoosh(0.18).map { $0 * 0.5 }
  }
  /// voiced vowel: harmonics of f0 weighted by two formant peaks; pitch falls by `drop` over the syllable
  private func formant(f0: Float, f1: Float, f2: Float, dur: Float, drop: Float) -> [Float] {
    let count = n(dur)
    var phase: Float = 0
    let nh = Int(5000 / f0)
    let amps: [Float] = (1...max(1, nh)).map { k in
      let f = Float(k) * f0
      return 1 / (1 + pow((f - f1) / 90, 2)) + 0.6 / (1 + pow((f - f2) / 220, 2)) + 0.04
    }
    return (0..<count).map { i in
      let t = Float(i) / sr, u = t / dur
      phase += 2 * .pi * f0 * (1 - drop * u) * (1 + 0.01 * sin(2 * .pi * 6 * t)) / sr
      var s: Float = 0
      for (k, a) in amps.enumerated() { s += a * sin(Float(k + 1) * phase) }
      let env = min(1, t / 0.006) * min(1, (dur - t) / 0.025)
      return s * env * 0.16
    }
  }
  private func giggle() -> [Float] {
    var out: [Float] = []
    for (k, f0) in [Float(720), 700, 660, 610].enumerated() {
      out += formant(f0: f0, f1: 300, f2: 2300, dur: k == 3 ? 0.13 : 0.085, drop: 0.12)
      out += [Float](repeating: 0, count: n(0.055))
    }
    return out
  }
}

/// tiny deterministic PRNG (SplitMix64) so babble is stable per line
struct SeededRNG {
  private var s: UInt64
  init(seed: UInt64) { s = seed }
  mutating func next() -> UInt64 {
    s &+= 0x9E3779B97F4A7C15
    var z = s
    z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9; z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
    return z ^ (z >> 31)
  }
}
