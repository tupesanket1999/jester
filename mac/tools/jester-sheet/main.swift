// Renders the jester rig offline: one row per state, columns = moments in time. Also --gif-frames for a strip.
// swiftc tools/jester-sheet/main.swift app/Jester.swift -o /tmp/jsheet && /tmp/jsheet out.png
import AppKit
let args = CommandLine.arguments
let out = args.count > 1 ? args[1] : "jester-sheet.png"
let allStates: [(String, String, [Double])] = [
  ("idle", "", [0.5, 1.5, 3.0, 4.0]), ("thinking", "", [0.5, 1.5, 3.2, 4.4]),
  ("working", "Bash", [0.4, 0.55, 0.7, 0.85]), ("working", "Edit", [0.5, 1.4, 2.6, 3.4]),
  ("working", "Read", [0.5, 1.2, 2.0, 2.8]), ("waiting", "", [0.1, 0.3, 0.55, 0.8]),
  ("done", "", [0.15, 0.45, 0.6, 0.75, 1.0, 1.5, 2.5, 4.0]), ("error", "", [0.1, 0.35, 0.6, 1.5]),
  ("remind", "", [0.3, 1.0, 2.0, 2.8]), ("sleep", "", [0.5, 2.0, 3.5, 5.0])]
let pick = args.count > 2 ? args[2].split(separator: ",").compactMap { Int($0) } : []
let states = pick.isEmpty ? allStates : pick.map { allStates[$0] }
let cell = CGSize(width: 170, height: 205), scale: CGFloat = 2, cols = 8
let W = Int(cell.width * CGFloat(cols) * scale), H = Int(cell.height * CGFloat(states.count) * scale)
let ctx = CGContext(data: nil, width: W, height: H, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
ctx.setFillColor(CGColor(srgbRed: 0.07, green: 0.04, blue: 0.05, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))
ctx.scaleBy(x: scale, y: scale)
NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
for (row, (st, tool, times)) in states.enumerated() {
  for (col, t) in times.enumerated() {
    let rig = JesterRig(); rig.zoom = 1; rig.drawGlow = true
    var clock = CACurrentMediaTime() + 5
    rig.request(state: st, tool: tool)
    // settle into the state (skip blend), then advance to time t at 120 fps
    rig.step(time: clock, windowDelta: .zero, mouseLocal: CGPoint(x: 140, y: 150), viewSize: cell)
    let end = clock + t
    while clock < end { clock += 1.0 / 120; rig.step(time: clock, windowDelta: .zero, mouseLocal: CGPoint(x: 140, y: 150), viewSize: cell) }
    ctx.saveGState()
    ctx.translateBy(x: CGFloat(col) * cell.width, y: CGFloat(states.count - 1 - row) * cell.height)
    rig.draw(in: ctx, size: cell)
    ctx.restoreGState()
  }
}
let img = ctx.makeImage()!
let rep = NSBitmapImageRep(cgImage: img)
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
print("wrote \(out)")
