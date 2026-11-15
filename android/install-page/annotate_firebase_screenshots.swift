#!/usr/bin/env swift
// Builds the images for the Firebase App Distribution tester guide
// (android/install-page/firebase/index.html) from raw 1080x2400 phone
// screenshots: masks the tester's Google account (avatar, name, e-mail) with a
// neutral placeholder, draws a red highlight around the tap target, and scales
// the result to 1000 px tall.
//
//   swift android/install-page/annotate_firebase_screenshots.swift <raw-dir> [out-dir]
//
// The raw screenshots stay outside git (they show a real account); only the
// masked output under android/install-page/firebase/images is committed.

import Foundation
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers

struct Mask {
    enum Kind {
        case circle(center: CGPoint, radius: CGFloat)
        case label(rect: CGRect, lines: [(String, CGFloat)])
        /// Repaint `rect` with `background` and re-typeset text that a hand-drawn
        /// marker had destroyed: (text, x, baseline y, font size) in raw coordinates.
        case redraw(rect: CGRect, background: CGColor, color: CGColor, lines: [(String, CGFloat, CGFloat, CGFloat)])
        /// Solid circle with one white letter in it (an app badge).
        case icon(center: CGPoint, radius: CGFloat, fill: CGColor, letter: String)
        /// Repaint a whole rounded button with the colour sampled from the drawn
        /// screenshot at `sampleAt` (so colour-profile conversion matches exactly)
        /// and centre a bold white label on it.
        case button(rect: CGRect, radius: CGFloat, sampleAt: CGPoint, label: String, size: CGFloat, baseline: CGFloat)
        /// Like `redraw`, but the background colour is sampled from the drawn
        /// screenshot at `sampleAt` instead of being given explicitly.
        case patch(rect: CGRect, sampleAt: CGPoint, color: CGColor, lines: [(String, CGFloat, CGFloat, CGFloat)])
    }
    let kind: Kind
}

let white = CGColor(red: 1, green: 1, blue: 1, alpha: 1)
let bodyText = CGColor(red: 0.125, green: 0.129, blue: 0.141, alpha: 1)   // Material #202124
let buttonBlue = CGColor(red: 0.0, green: 0.459, blue: 0.941, alpha: 1)   // sampled from the screenshots: (0,117,240)
let secondaryText = CGColor(red: 0.373, green: 0.388, blue: 0.408, alpha: 1) // #5f6368
let divider = CGColor(red: 0.855, green: 0.863, blue: 0.878, alpha: 1)      // #dadce0
let consentBox = CGColor(red: 0.933, green: 0.933, blue: 0.933, alpha: 1)   // #eeeeee
let consentText = CGColor(red: 0.29, green: 0.30, blue: 0.31, alpha: 1)
let teal = CGColor(red: 0.0, green: 0.78, blue: 0.66, alpha: 1)             // Firebase app badge
let urlText = CGColor(red: 0.78, green: 0.78, blue: 0.80, alpha: 1)         // Chrome's URL line in the dark title bar

struct Job {
    let source: String
    let output: String
    let highlights: [CGRect]
    let masks: [Mask]
    /// Some raw screenshots carry a hand-drawn red circle; erase it by copying
    /// the nearest non-red pixel from the left (the backgrounds are flat).
    var eraseHandDrawnRed: Bool = false
}

func erasingRed(_ image: CGImage) -> CGImage? {
    let w = image.width, h = image.height
    let space = CGColorSpaceCreateDeviceRGB()
    guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                              space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
          let base = ctx.data else { return nil }
    ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
    let p = base.bindMemory(to: UInt8.self, capacity: w * h * 4)
    // The marker is a saturated red (~255,59,48). Orange UI (App Tester icon,
    // ~255,160,0) has a much higher green channel and must be left alone.
    var red = [Bool](repeating: false, count: w * h)
    for i in 0..<(w * h) {
        let r = Int(p[i * 4]), g = Int(p[i * 4 + 1]), b = Int(p[i * 4 + 2])
        // Pure marker, or the marker blended with the blue buttons (purple fringe).
        red[i] = (r > 110 && g < 105 && b < 105 && r > g + 55 && r > b + 55)
              || (g < 100 && r > g + 30 && b > g + 30 && r > 100)
    }
    // Grow the mask by one pixel so anti-aliased fringes are replaced too.
    var grown = red
    for y in 0..<h { for x in 0..<w where red[y * w + x] {
        for dy in -1...1 { for dx in -1...1 {
            let nx = x + dx, ny = y + dy
            if nx >= 0, ny >= 0, nx < w, ny < h { grown[ny * w + nx] = true }
        } }
    } }
    // Fill each marked pixel from the nearest unmarked pixel along the four axes.
    let src = Array(UnsafeBufferPointer(start: p, count: w * h * 4))
    for y in 0..<h { for x in 0..<w where grown[y * w + x] {
        var found = -1
        var d = 1
        while found < 0 && d < 80 {
            for (dx, dy) in [(-d, 0), (d, 0), (0, -d), (0, d)] {
                let nx = x + dx, ny = y + dy
                if nx >= 0, ny >= 0, nx < w, ny < h, !grown[ny * w + nx] { found = ny * w + nx; break }
            }
            d += 1
        }
        if found >= 0 {
            let i = (y * w + x) * 4, j = found * 4
            p[i] = src[j]; p[i + 1] = src[j + 1]; p[i + 2] = src[j + 2]
        }
    } }
    return ctx.makeImage()
}

// Raw coordinates (top-left origin). The tester page shows the account avatar at
// the top right; the acceptance page repeats it next to the name and e-mail.
let topRightAvatar = Mask(kind: .circle(center: CGPoint(x: 994, y: 378), radius: 52))
let accountRowAvatar = Mask(kind: .circle(center: CGPoint(x: 90, y: 1266), radius: 52))
let accountRowText = Mask(kind: .label(rect: CGRect(x: 170, y: 1196, width: 520, height: 150),
                                       lines: [("テスター名", 44), ("tester@gmail.com", 34)]))

let jobs: [Job] = [
    // A. accept the invitation
    Job(source: "1000000119.png", output: "a1-mail.png",
        highlights: [CGRect(x: 330, y: 1750, width: 420, height: 66)], masks: []),
    Job(source: "1000000120.png", output: "a2-get-started.png",
        highlights: [CGRect(x: 228, y: 1020, width: 276, height: 102)], masks: []),
    Job(source: "1000000121.png", output: "a3-consent.png",
        highlights: [CGRect(x: 92, y: 1590, width: 70, height: 70)],
        masks: [topRightAvatar, accountRowAvatar, accountRowText]),
    Job(source: "1000000122.png", output: "a4-accept.png",
        highlights: [CGRect(x: 43, y: 1944, width: 994, height: 96)],
        masks: [topRightAvatar, accountRowAvatar, accountRowText]),
    // B. install App Tester
    Job(source: "1000000123.png", output: "b1-app-tester-button.png",
        highlights: [CGRect(x: 386, y: 2196, width: 548, height: 102)], masks: [topRightAvatar]),
    Job(source: "1000000132.png", output: "b2-notification-hint.png",
        highlights: [CGRect(x: 22, y: 306, width: 1034, height: 174)],
        masks: [// (no avatar mask: the opaque toast covers it in this screenshot)
                // The toast text itself
                Mask(kind: .patch(rect: CGRect(x: 40, y: 330, width: 990, height: 140), sampleAt: CGPoint(x: 1030, y: 330), color: white,
                                  lines: [("下の [開く] をクリックするか通知をスワイプして、App", 62, 380, 36),
                                          ("Tester を確認し、インストールしてください", 62, 432, 36)])),
                // Chrome's title bar and the page heading were under the marker; retype both.
                Mask(kind: .patch(rect: CGRect(x: 290, y: 160, width: 510, height: 112), sampleAt: CGPoint(x: 150, y: 200), color: white,
                                  lines: [("Firebase App Distributi...", 300, 202, 40)])),
                Mask(kind: .patch(rect: CGRect(x: 290, y: 222, width: 510, height: 50), sampleAt: CGPoint(x: 150, y: 200), color: urlText,
                                  lines: [("tribution.firebase.google.com", 300, 250, 34)])),
                Mask(kind: .patch(rect: CGRect(x: 230, y: 490, width: 770, height: 130), sampleAt: CGPoint(x: 900, y: 560), color: bodyText,
                                  lines: [("*Code Match", 240, 538, 48)])),
                Mask(kind: .patch(rect: CGRect(x: 230, y: 556, width: 770, height: 64), sampleAt: CGPoint(x: 900, y: 560), color: secondaryText,
                                  lines: [("jp.rimtty.codematch", 240, 595, 36)]))],
        eraseHandDrawnRed: true),
    Job(source: "1000000133.png", output: "b3-redownload.png",
        highlights: [CGRect(x: 84, y: 1284, width: 282, height: 54)], masks: [topRightAvatar], eraseHandDrawnRed: true),
    Job(source: "1000000134.png", output: "b4-install.png",
        highlights: [CGRect(x: 630, y: 1428, width: 304, height: 96)], masks: [topRightAvatar]),
    Job(source: "1000000136.png", output: "b5-open.png",
        highlights: [CGRect(x: 702, y: 1428, width: 216, height: 96)], masks: [topRightAvatar], eraseHandDrawnRed: true),
    // C. inside App Tester
    Job(source: "1000000137.png", output: "d1-signin.png",
        highlights: [CGRect(x: 298, y: 1314, width: 484, height: 102)], masks: [], eraseHandDrawnRed: true),
    Job(source: "1000000138.png", output: "d2-account.png",
        highlights: [CGRect(x: 100, y: 1090, width: 860, height: 130)],
        masks: [Mask(kind: .circle(center: CGPoint(x: 156, y: 1152), radius: 46)),
                Mask(kind: .label(rect: CGRect(x: 222, y: 1086, width: 618, height: 150),
                                  lines: [("テスター名", 40), ("tester@gmail.com", 30)])),
                Mask(kind: .circle(center: CGPoint(x: 156, y: 1326), radius: 46)),
                Mask(kind: .label(rect: CGRect(x: 222, y: 1260, width: 618, height: 150),
                                  lines: [("別のアカウント名", 40), ("another@gmail.com", 30)]))],
        eraseHandDrawnRed: true),
    Job(source: "1000000139.png", output: "d3-list.png",
        highlights: [CGRect(x: 43, y: 528, width: 1000, height: 216)],
        masks: [Mask(kind: .circle(center: CGPoint(x: 984, y: 236), radius: 52)),
                // The marker crossed the app icon; repaint it as the teal "C" badge used elsewhere.
                Mask(kind: .icon(center: CGPoint(x: 108, y: 605), radius: 66, fill: teal, letter: "C")),
                // Retype the app name / package / release count the marker ran through.
                Mask(kind: .redraw(rect: CGRect(x: 200, y: 545, width: 860, height: 185), background: white, color: bodyText,
                                   lines: [("*Code Match", 215, 595, 48)])),
                Mask(kind: .redraw(rect: CGRect(x: 200, y: 612, width: 860, height: 118), background: white, color: secondaryText,
                                   lines: [("jp.rimtty.codematch", 215, 652, 36), ("1 件のリリース", 215, 710, 36)])),
                // and the divider under the row
                Mask(kind: .redraw(rect: CGRect(x: 36, y: 756, width: 1008, height: 3), background: divider, color: white, lines: []))],
        eraseHandDrawnRed: true),
    Job(source: "1000000140.png", output: "d4-consent.png",
        highlights: [CGRect(x: 96, y: 1162, width: 50, height: 50), CGRect(x: 43, y: 1622, width: 994, height: 102)],
        masks: [// first three lines of the consent text
                Mask(kind: .redraw(rect: CGRect(x: 160, y: 1160, width: 850, height: 162), background: consentBox, color: consentText,
                                   lines: [("jp.rimtty.codematch のデベロッパーが私のデバイス上で", 168, 1200, 34),
                                           ("行うテスト用アプリのテストを促進する目的で、", 168, 1252, 34),
                                           ("Firebase が私の氏名、使用デバイスのメーカー、モデ", 168, 1306, 34)])),
                // the button label
                // white gaps above and below the button that the marker crossed
                Mask(kind: .redraw(rect: CGRect(x: 200, y: 1565, width: 680, height: 55), background: white, color: white, lines: [])),
                Mask(kind: .redraw(rect: CGRect(x: 250, y: 1732, width: 450, height: 60), background: white, color: white, lines: [])),
                Mask(kind: .button(rect: CGRect(x: 43, y: 1620, width: 994, height: 108), radius: 30, sampleAt: CGPoint(x: 150, y: 1700),
                                   label: "このデバイスでテストを開始", size: 40, baseline: 1688))],
        eraseHandDrawnRed: true),
    Job(source: "1000000141.png", output: "d5-open.png",
        highlights: [CGRect(x: 43, y: 1368, width: 315, height: 98)],
        masks: [Mask(kind: .circle(center: CGPoint(x: 984, y: 236), radius: 52)),
                // The marker also nicked the top edge of the blue 開く button.
                Mask(kind: .redraw(rect: CGRect(x: 140, y: 1330, width: 260, height: 37), background: white, color: white, lines: [])),
                Mask(kind: .button(rect: CGRect(x: 43, y: 1368, width: 315, height: 98), radius: 30, sampleAt: CGPoint(x: 100, y: 1440),
                                   label: "開く", size: 40, baseline: 1432)),
                // The marker ran through the first three release-note lines; retype them.
                Mask(kind: .redraw(rect: CGRect(x: 122, y: 1508, width: 900, height: 146), background: white, color: bodyText,
                                   lines: [("Code Match 0.1.0 (1) (2026-09-11)", 132, 1548, 35),
                                           ("インストール手順（画面つき）: https://dl", 132, 1596, 35),
                                           ("-aaa.s3.ap-northeast-1.amazonaws.com", 132, 1643, 35)]))],
        eraseHandDrawnRed: true),
    // E. download Code Match from the web page (alternative route)
    Job(source: "1000000127.png", output: "c1-download.png",
        highlights: [CGRect(x: 54, y: 1224, width: 366, height: 96)], masks: [topRightAvatar]),
    Job(source: "1000000125.png", output: "c2-warning.png",
        highlights: [CGRect(x: 654, y: 1506, width: 336, height: 56)], masks: [topRightAvatar]),
]

let args = CommandLine.arguments
guard args.count >= 2 else {
    FileHandle.standardError.write("usage: annotate_firebase_screenshots.swift <raw-dir> [out-dir]\n".data(using: .utf8)!)
    exit(2)
}
let rawDir = URL(fileURLWithPath: args[1])
let outDir = URL(fileURLWithPath: args.count >= 3 ? args[2] : "android/install-page/firebase/images")
try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)

let targetHeight: CGFloat = 1000
let lineWidth: CGFloat = 10
let padding: CGFloat = 18
let strokeColor = CGColor(red: 0.90, green: 0.16, blue: 0.13, alpha: 1)
let maskFill = CGColor(red: 0.80, green: 0.80, blue: 0.82, alpha: 1)
let maskText = CGColor(red: 0.25, green: 0.25, blue: 0.27, alpha: 1)

func loadImage(_ url: URL) -> CGImage? {
    guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
    return CGImageSourceCreateImageAtIndex(src, 0, nil)
}

func writePNG(_ image: CGImage, to url: URL) -> Bool {
    guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else { return false }
    CGImageDestinationAddImage(dest, image, nil)
    return CGImageDestinationFinalize(dest)
}

/// Converts a top-left-origin raw rect to the flipped, scaled output rect.
func outRect(_ r: CGRect, scale: CGFloat, outH: CGFloat) -> CGRect {
    CGRect(x: r.minX * scale, y: outH - r.maxY * scale, width: r.width * scale, height: r.height * scale)
}

/// Colour of the already-drawn screenshot at a raw (top-left origin) point.
/// Bitmap rows are stored top-down, so the point maps directly into memory.
func sampledColor(_ ctx: CGContext, at raw: CGPoint, scale: CGFloat) -> CGColor {
    let sx = Int(raw.x * scale), sy = Int(raw.y * scale)
    let bpr = ctx.bytesPerRow
    let p = ctx.data!.bindMemory(to: UInt8.self, capacity: bpr * ctx.height)
    let o = sy * bpr + sx * 4
    return CGColor(red: CGFloat(p[o]) / 255, green: CGFloat(p[o + 1]) / 255, blue: CGFloat(p[o + 2]) / 255, alpha: 1)
}

func textWidth(_ text: String, size: CGFloat, weight: String) -> CGFloat {
    let font = CTFontCreateWithName("HiraginoSans-\(weight)" as CFString, size, nil)
    let attrs: [CFString: Any] = [kCTFontAttributeName: font]
    let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attrs as [NSAttributedString.Key: Any]))
    return CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
}

func drawText(_ text: String, size: CGFloat, at origin: CGPoint, in ctx: CGContext, color: CGColor = maskText, weight: String = "W4") {
    let font = CTFontCreateWithName("HiraginoSans-\(weight)" as CFString, size, nil)
    let attrs: [CFString: Any] = [kCTFontAttributeName: font, kCTForegroundColorAttributeName: color]
    let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attrs as [NSAttributedString.Key: Any]))
    ctx.textPosition = origin
    CTLineDraw(line, ctx)
}

var failures = 0
for job in jobs {
    let inURL = rawDir.appendingPathComponent(job.source)
    guard var image = loadImage(inURL) else {
        FileHandle.standardError.write("missing or unreadable: \(inURL.path)\n".data(using: .utf8)!)
        failures += 1
        continue
    }
    if job.eraseHandDrawnRed, let cleaned = erasingRed(image) { image = cleaned }
    let srcW = CGFloat(image.width), srcH = CGFloat(image.height)
    let scale = targetHeight / srcH
    let outW = Int((srcW * scale).rounded()), outH = Int(targetHeight)
    let space = CGColorSpaceCreateDeviceRGB()
    guard let ctx = CGContext(data: nil, width: outW, height: outH, bitsPerComponent: 8, bytesPerRow: 0,
                              space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
        failures += 1
        continue
    }
    ctx.interpolationQuality = .high
    ctx.draw(image, in: CGRect(x: 0, y: 0, width: CGFloat(outW), height: CGFloat(outH)))

    // Masks first, so the highlight is drawn on top of them.
    for mask in job.masks {
        ctx.setFillColor(maskFill)
        switch mask.kind {
        case .circle(let center, let radius):
            let r = CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)
            ctx.fillEllipse(in: outRect(r, scale: scale, outH: CGFloat(outH)))
        case .patch(let rect, let sampleAt, let color, let lines):
            ctx.setFillColor(sampledColor(ctx, at: sampleAt, scale: scale))
            let o = outRect(rect, scale: scale, outH: CGFloat(outH))
            ctx.fill(o)
            for (text, x, baseline, size) in lines {
                let bold = text.hasPrefix("*")
                let body = bold ? String(text.dropFirst()) : text
                let weight = bold ? "W6" : "W3"
                var ox = x * scale
                if x < 0 { ox = o.midX - textWidth(body, size: size * scale, weight: weight) / 2 }
                drawText(body, size: size * scale, at: CGPoint(x: ox, y: CGFloat(outH) - baseline * scale),
                         in: ctx, color: color, weight: weight)
            }
        case .button(let rect, let radius, let sampleAt, let label, let size, let baseline):
            let fill = sampledColor(ctx, at: sampleAt, scale: scale)
            let r = outRect(rect, scale: scale, outH: CGFloat(outH))
            ctx.setFillColor(fill)
            ctx.addPath(CGPath(roundedRect: r, cornerWidth: radius * scale, cornerHeight: radius * scale, transform: nil))
            ctx.fillPath()
            let s = size * scale
            let wdt = textWidth(label, size: s, weight: "W6")
            drawText(label, size: s, at: CGPoint(x: r.midX - wdt / 2, y: CGFloat(outH) - baseline * scale), in: ctx, color: white, weight: "W6")
        case .icon(let center, let radius, let fill, let letter):
            let r = CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)
            let o = outRect(r, scale: scale, outH: CGFloat(outH))
            ctx.setFillColor(fill)
            ctx.fillEllipse(in: o)
            let size = radius * 1.1 * scale
            let wdt = textWidth(letter, size: size, weight: "W3")
            drawText(letter, size: size, at: CGPoint(x: o.midX - wdt / 2, y: o.midY - size * 0.36), in: ctx, color: white, weight: "W3")
        case .redraw(let rect, let background, let color, let lines):
            ctx.setFillColor(background)
            let o = outRect(rect, scale: scale, outH: CGFloat(outH))
            ctx.fill(o)
            for (text, x, baseline, size) in lines {
                // x < 0 centres the line inside the rect; a "*" prefix selects the bold face.
                let bold = text.hasPrefix("*")
                let body = bold ? String(text.dropFirst()) : text
                let weight = bold ? "W6" : "W3"
                var ox = x * scale
                if x < 0 { ox = o.midX - textWidth(body, size: size * scale, weight: weight) / 2 }
                drawText(body, size: size * scale, at: CGPoint(x: ox, y: CGFloat(outH) - baseline * scale),
                         in: ctx, color: color, weight: weight)
            }
        case .label(let rect, let lines):
            let o = outRect(rect, scale: scale, outH: CGFloat(outH))
            ctx.addPath(CGPath(roundedRect: o, cornerWidth: 8, cornerHeight: 8, transform: nil))
            ctx.fillPath()
            // Lay the lines out from the top of the box.
            var y = o.maxY - 12
            for (text, size) in lines {
                let s = size * scale
                y -= s
                drawText(text, size: s, at: CGPoint(x: o.minX + 10, y: y), in: ctx)
                y -= 8
            }
        }
    }

    ctx.setStrokeColor(strokeColor)
    ctx.setLineWidth(lineWidth * scale * 2)
    for r in job.highlights {
        let o = outRect(r.insetBy(dx: -padding, dy: -padding), scale: scale, outH: CGFloat(outH))
        let radius = min(o.width, o.height) / 2
        ctx.addPath(CGPath(roundedRect: o, cornerWidth: radius, cornerHeight: radius, transform: nil))
        ctx.strokePath()
    }

    guard let out = ctx.makeImage(), writePNG(out, to: outDir.appendingPathComponent(job.output)) else {
        FileHandle.standardError.write("failed to write \(job.output)\n".data(using: .utf8)!)
        failures += 1
        continue
    }
    print("wrote \(job.output) (\(outW)x\(outH))")
}
exit(failures == 0 ? 0 : 1)
