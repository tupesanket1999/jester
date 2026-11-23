// Renders every state's first two frames of a bot pack to a PNG contact sheet.
// usage: swift tools/preview.swift <packdir> <out.png>
import Foundation
import AppKit

struct Layer { let x: Int; let y: Int; let rows: [String] }
let args = CommandLine.arguments
let dir = URL(fileURLWithPath: args[1])
let manifest = try! JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent("bot.json"))) as! [String: Any]
let text = try! String(contentsOf: dir.appendingPathComponent(manifest["parts"] as! String), encoding: .utf8)
var palette: [Character: NSColor] = [:]
var layers: [String: Layer] = [:]
var mode = ""; var cur = ""; var cx = 0; var cy = 0; var rows: [String] = []
func flush() { if !cur.isEmpty { layers[cur] = Layer(x: cx, y: cy, rows: rows) }; cur = ""; rows = [] }
for raw in text.split(separator: "\n", omittingEmptySubsequences: false) {
  let line = String(raw)
  if line.hasPrefix("#") { continue }
  if line.hasPrefix("@palette") { flush(); mode = "p"; continue }
  if line.hasPrefix("@layer") { flush(); let p = line.split(separator: " "); cur = String(p[1]); cx = Int(p[2])!; cy = Int(p[3])!; mode = "l"; continue }
  if line.trimmingCharacters(in: .whitespaces).isEmpty { continue }
  if mode == "p" { let p = line.split(separator: " "); palette[p[0].first!] = NSColor(srgbRed: CGFloat(Int(p[1])!)/255, green: CGFloat(Int(p[2])!)/255, blue: CGFloat(Int(p[3])!)/255, alpha: 1) }
  else if mode == "l" { rows.append(line) }
}
flush()
let states = manifest["states"] as! [String: [[Any]]]
let order = ["idle","thinking","working","waiting","done","error","remind","sleep"].filter { states[$0] != nil }
let W = 24, H = 28, S = 6, pad = 12
let cellW = W*S + pad*2, cellH = H*S + pad*2 + 22
let img = NSImage(size: NSSize(width: cellW * order.count, height: cellH * 2))
img.lockFocus()
NSColor(srgbRed: 92/255, green: 84/255, blue: 98/255, alpha: 1).setFill()
NSRect(x: 0, y: 0, width: img.size.width, height: img.size.height).fill()
for (i, st) in order.enumerated() {
  for (f, frame) in states[st]!.prefix(2).enumerated() {
    let ox = i * cellW + pad, oyTop = f * cellH + pad + 22
    for item in frame {
      var name = ""; var dx = 0; var dy = 0
      if let s = item as? String { name = s } else if let d = item as? [String: Any] { name = d["layer"] as! String; dx = d["dx"] as? Int ?? 0; dy = d["dy"] as? Int ?? 0 }
      guard let L = layers[name] else { print("missing layer \(name)"); continue }
      for (r, row) in L.rows.enumerated() { for (c, ch) in row.enumerated() {
        guard ch != ".", let col = palette[ch] else { continue }
        col.setFill()
        let px = ox + (L.x + c + dx) * S, py = oyTop + (L.y + r + dy) * S
        NSRect(x: px, y: Int(img.size.height) - py - S, width: S, height: S).fill()
      } }
    }
    let label = "\(st) \(f+1)" as NSString
    label.draw(at: NSPoint(x: ox, y: Int(img.size.height) - (f * cellH + pad) - 16), withAttributes: [.foregroundColor: NSColor(srgbRed: 242/255, green: 167/255, blue: 160/255, alpha: 1), .font: NSFont.monospacedSystemFont(ofSize: 13, weight: .medium)])
  }
}
img.unlockFocus()
let rep = NSBitmapImageRep(data: img.tiffRepresentation!)!
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: args[2]))
// row-width check
for (n, L) in layers { let w = Set(L.rows.map { $0.count }); if w.count > 1 { print("WARN uneven rows in \(n): \(w)") } }
print("wrote \(args[2]) states=\(order.count) layers=\(layers.count)")
