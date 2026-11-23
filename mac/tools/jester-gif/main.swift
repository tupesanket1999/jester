// Renders a short choreographed demo of the jester rig to an animated GIF (for the README / releases).
// swiftc -O tools/jester-gif/main.swift app/Jester.swift -o /tmp/jgif && /tmp/jgif ../docs/jester.gif
// Optional args: <out.gif> <scale=1.5> <fps=25>
// Pose strip instead: /tmp/jgif --strip ../docs/media/jester-poses.png
import AppKit
import ImageIO
import UniformTypeIdentifiers

let args = CommandLine.arguments
if args.count > 2, args[1] == "--strip" { renderStrip(to: args[2]); exit(0) }
let out = args.count > 1 ? args[1] : "jester.gif"
let scale = args.count > 2 ? CGFloat(Double(args[2]) ?? 1.5) : 1.5
let fps = args.count > 3 ? Double(args[3]) ?? 25 : 25

// (state, tool, seconds): Claude thinks, works, needs your OK, finishes (backflip), dozes off.
let script: [(String, String, Double)] = [
  ("idle", "", 2.2), ("thinking", "", 1.8), ("working", "Edit", 1.8), ("working", "Bash", 1.4),
  ("waiting", "", 1.8), ("done", "", 3.2), ("idle", "", 1.6), ("sleep", "", 2.2)]

let cell = CGSize(width: 170, height: 205)
let W = Int(cell.width * scale), H = Int(cell.height * scale)
let total = script.reduce(0) { $0 + $1.2 }
let frames = Int(total * fps)

guard let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: out) as CFURL, UTType.gif.identifier as CFString,
                                                 frames, nil) else { fatalError("cannot create \(out)") }
CGImageDestinationSetProperties(dest, [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFLoopCount: 0]] as CFDictionary)
let frameProps = [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: 1.0 / fps,
                                                  kCGImagePropertyGIFUnclampedDelayTime: 1.0 / fps]] as CFDictionary

let rig = JesterRig(); rig.zoom = 1; rig.drawGlow = true
var clock = CACurrentMediaTime() + 5
let mouse = CGPoint(x: 140, y: 150)
// warm up so springs and blink timers are settled
for _ in 0..<120 { clock += 1.0 / 120; rig.step(time: clock, windowDelta: .zero, mouseLocal: mouse, viewSize: cell) }

var seg = 0, segEnd = script[0].2
rig.request(state: script[0].0, tool: script[0].1)
for f in 0..<frames {
  let t = Double(f) / fps
  if t >= segEnd, seg + 1 < script.count {
    seg += 1; segEnd += script[seg].2
    rig.request(state: script[seg].0, tool: script[seg].1)
  }
  // advance physics at 120 Hz between frames
  let target = clock + 1.0 / fps
  while clock < target { clock += 1.0 / 120; rig.step(time: clock, windowDelta: .zero, mouseLocal: mouse, viewSize: cell) }

  let ctx = CGContext(data: nil, width: W, height: H, bitsPerComponent: 8, bytesPerRow: 0,
                      space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
  ctx.setFillColor(CGColor(srgbRed: 0.07, green: 0.04, blue: 0.05, alpha: 1))
  ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))
  ctx.scaleBy(x: scale, y: scale)
  NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
  rig.draw(in: ctx, size: cell)
  CGImageDestinationAddImage(dest, ctx.makeImage()!, frameProps)
}
guard CGImageDestinationFinalize(dest) else { fatalError("GIF write failed") }
print("wrote \(out): \(frames) frames, \(W)x\(H), \(String(format: "%.1f", total)) s")

/// One labelled row of key poses, each sampled at a moment that reads well in a still.
func renderStrip(to path: String) {
  let poses: [(String, String, Double, String)] = [
    ("idle", "", 1.5, "idle"), ("thinking", "", 1.5, "thinking"), ("working", "Edit", 1.4, "editing"),
    ("working", "Bash", 0.7, "running"), ("waiting", "", 0.8, "needs your OK"), ("done", "", 0.45, "done: backflip"),
    ("error", "", 0.6, "error"), ("sleep", "", 3.5, "asleep")]
  let cell = CGSize(width: 170, height: 230), s: CGFloat = 2
  let W = Int(cell.width * CGFloat(poses.count) * s), H = Int(cell.height * s)
  let ctx = CGContext(data: nil, width: W, height: H, bitsPerComponent: 8, bytesPerRow: 0,
                      space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
  ctx.setFillColor(CGColor(srgbRed: 0.07, green: 0.04, blue: 0.05, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))
  ctx.scaleBy(x: s, y: s)
  NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
  let rigCell = CGSize(width: 170, height: 205)
  for (i, (st, tool, t, label)) in poses.enumerated() {
    let rig = JesterRig(); rig.zoom = 1; rig.drawGlow = true
    var clock = CACurrentMediaTime() + 5
    rig.request(state: st, tool: tool)
    rig.step(time: clock, windowDelta: .zero, mouseLocal: CGPoint(x: 140, y: 150), viewSize: rigCell)
    let end = clock + t
    while clock < end { clock += 1.0 / 120; rig.step(time: clock, windowDelta: .zero, mouseLocal: CGPoint(x: 140, y: 150), viewSize: rigCell) }
    ctx.saveGState(); ctx.translateBy(x: CGFloat(i) * cell.width, y: cell.height - rigCell.height)
    rig.draw(in: ctx, size: rigCell); ctx.restoreGState()
    let attrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 13, weight: .semibold),
                                                .foregroundColor: NSColor(srgbRed: 0.95, green: 0.82, blue: 0.62, alpha: 1)]
    let str = NSAttributedString(string: label, attributes: attrs)
    let sz = str.size()
    str.draw(at: CGPoint(x: CGFloat(i) * cell.width + (cell.width - sz.width) / 2, y: 10))
  }
  let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
  try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
  print("wrote \(path)")
}
