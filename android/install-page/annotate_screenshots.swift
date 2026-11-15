#!/usr/bin/env swift
// Draws a red rounded highlight around the tap target of each install-page
// screenshot and scales the result to 1000 px tall.
//
//   swift android/install-page/annotate_screenshots.swift <raw-screenshot-dir> [out-dir]
//
// Raw screenshots are 1080x2400 `adb exec-out screencap -p` captures (not kept in
// git: they contain nothing secret, but re-capturing is the documented path in
// docs/android). The table below maps each published image to its source file
// and the highlight rectangle in raw pixel coordinates (x, y, width, height).

import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

struct Job {
    let source: String
    let output: String
    let rects: [CGRect]
}

let jobs: [Job] = [
    Job(source: "v1.png", output: "01-page.png", rects: [CGRect(x: 42, y: 816, width: 996, height: 240)]),
    Job(source: "shot-02.png", output: "02-warning.png", rects: [CGRect(x: 615, y: 1494, width: 420, height: 90)]),
    Job(source: "shot-03a.png", output: "03-complete.png", rects: [CGRect(x: 855, y: 354, width: 135, height: 90)]),
    Job(source: "shot-05-menu.png", output: "04-menu.png", rects: [CGRect(x: 405, y: 1020, width: 630, height: 96)]),
    Job(source: "shot-06-downloads.png", output: "05-downloads.png", rects: [CGRect(x: 30, y: 555, width: 960, height: 150)]),
    Job(source: "shot-07.png", output: "06-permission.png", rects: [CGRect(x: 735, y: 1524, width: 165, height: 96)]),
    Job(source: "shot-08-settings.png", output: "07-allow.png", rects: [CGRect(x: 840, y: 780, width: 165, height: 120)]),
    Job(source: "shot-11-install-dialog.png", output: "08-install.png", rects: [CGRect(x: 630, y: 1410, width: 306, height: 120)]),
    Job(source: "shot-12c.png", output: "09-protect.png", rects: [CGRect(x: 105, y: 1494, width: 195, height: 90)]),
    Job(source: "shot-13-protect-details.png", output: "10-protect-details.png", rects: [CGRect(x: 105, y: 1596, width: 345, height: 102)]),
    Job(source: "shot-14a.png", output: "11-done.png", rects: [CGRect(x: 714, y: 1410, width: 222, height: 120)]),
    Job(source: "r1.png", output: "12-redownload.png", rects: [CGRect(x: 210, y: 1116, width: 735, height: 84)]),
]

let args = CommandLine.arguments
guard args.count >= 2 else {
    FileHandle.standardError.write("usage: annotate_screenshots.swift <raw-dir> [out-dir]\n".data(using: .utf8)!)
    exit(2)
}
let rawDir = URL(fileURLWithPath: args[1])
let outDir = URL(fileURLWithPath: args.count >= 3 ? args[2] : "android/install-page/images")
try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)

let targetHeight: CGFloat = 1000
let lineWidth: CGFloat = 12
let padding: CGFloat = 18
let strokeColor = CGColor(red: 0.90, green: 0.16, blue: 0.13, alpha: 1)

func loadImage(_ url: URL) -> CGImage? {
    guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
    return CGImageSourceCreateImageAtIndex(src, 0, nil)
}

func writePNG(_ image: CGImage, to url: URL) -> Bool {
    guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else { return false }
    CGImageDestinationAddImage(dest, image, nil)
    return CGImageDestinationFinalize(dest)
}

var failures = 0
for job in jobs {
    let inURL = rawDir.appendingPathComponent(job.source)
    guard let image = loadImage(inURL) else {
        FileHandle.standardError.write("missing or unreadable: \(inURL.path)\n".data(using: .utf8)!)
        failures += 1
        continue
    }
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

    ctx.setStrokeColor(strokeColor)
    ctx.setLineWidth(lineWidth * scale * 2) // line width is in output pixels; keep it visible after scaling
    for r in job.rects {
        // Raw coordinates are top-left origin; CoreGraphics is bottom-left.
        let padded = r.insetBy(dx: -padding, dy: -padding)
        let flipped = CGRect(x: padded.minX * scale,
                             y: CGFloat(outH) - (padded.maxY * scale),
                             width: padded.width * scale,
                             height: padded.height * scale)
        let radius = min(flipped.width, flipped.height) / 2
        let path = CGPath(roundedRect: flipped, cornerWidth: radius, cornerHeight: radius, transform: nil)
        ctx.addPath(path)
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
