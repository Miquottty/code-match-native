#!/usr/bin/env swift
// List the newest TestFlight builds of CodeMatch with their processing state
// and "What to Test" text, via the App Store Connect API.
//
// Usage:
//   swift ios/tools/list_testflight_builds.swift [count]
//
// App Store Connect renumbers an upload whose build number already exists
// (manageAppVersionAndBuildNumber), so check here which number an upload
// really got before setting notes with set_testflight_whats_new.swift, and
// keep CURRENT_PROJECT_VERSION in step with it.
//
// Requires ~/.appstoreconnect/private_keys/AuthKey_<KEY_ID>.p8.

import Foundation
import CryptoKit

let appID = "6803057393" // CodeMatch
let keyID = "6A5T6F2445"
let issuerID = "ac1a8863-ddd2-4fbf-8618-7d74eccd4e2f"

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data(("error: " + message + "\n").utf8))
    exit(1)
}

let count = CommandLine.arguments.count > 1 ? (Int(CommandLine.arguments[1]) ?? 8) : 8

// --- JWT (ES256) ---
let keyPath = ("~/.appstoreconnect/private_keys/AuthKey_\(keyID).p8" as NSString).expandingTildeInPath
guard let pem = try? String(contentsOfFile: keyPath, encoding: .utf8),
      let key = try? P256.Signing.PrivateKey(pemRepresentation: pem) else {
    fail("could not load private key at \(keyPath)")
}

func base64URL(_ data: Data) -> String {
    data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

let now = Int(Date().timeIntervalSince1970)
let header = ["alg": "ES256", "kid": keyID, "typ": "JWT"]
let payload: [String: Any] = ["iss": issuerID, "iat": now, "exp": now + 600, "aud": "appstoreconnect-v1"]
let signingInput = base64URL(try! JSONSerialization.data(withJSONObject: header))
    + "." + base64URL(try! JSONSerialization.data(withJSONObject: payload))
let signature = try! key.signature(for: Data(signingInput.utf8))
let jwt = signingInput + "." + base64URL(signature.rawRepresentation)

// --- Minimal synchronous ASC API client ---
func request(_ method: String, _ url: String, body: [String: Any]? = nil) -> [String: Any] {
    var req = URLRequest(url: URL(string: url)!)
    req.httpMethod = method
    req.setValue("Bearer \(jwt)", forHTTPHeaderField: "Authorization")
    if let body {
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try! JSONSerialization.data(withJSONObject: body)
    }
    var result: [String: Any] = [:]
    var status = 0
    let semaphore = DispatchSemaphore(value: 0)
    URLSession.shared.dataTask(with: req) { data, response, error in
        if let error { fail("request failed: \(error.localizedDescription)") }
        status = (response as! HTTPURLResponse).statusCode
        if let data, !data.isEmpty,
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            result = json
        }
        semaphore.signal()
    }.resume()
    semaphore.wait()
    guard (200..<300).contains(status) else {
        fail("\(method) \(url) returned \(status): \(result)")
    }
    return result
}

let buildsURL = "https://api.appstoreconnect.apple.com/v1/builds?filter[app]=\(appID)&sort=-uploadedDate&limit=\(count)"
let builds = (request("GET", buildsURL)["data"] as? [[String: Any]]) ?? []
if builds.isEmpty { print("no builds") }
for build in builds {
    let attributes = build["attributes"] as? [String: Any] ?? [:]
    let number = attributes["version"] as? String ?? "?"
    let state = attributes["processingState"] as? String ?? "?"
    let uploaded = attributes["uploadedDate"] as? String ?? "?"
    let expired = (attributes["expired"] as? Bool) == true ? " expired" : ""
    var notes = "(no notes)"
    if let id = build["id"] as? String {
        let locs = (request("GET", "https://api.appstoreconnect.apple.com/v1/builds/\(id)/betaBuildLocalizations?limit=50")["data"]
            as? [[String: Any]]) ?? []
        let texts = locs.compactMap { loc -> String? in
            let a = loc["attributes"] as? [String: Any]
            guard let text = a?["whatsNew"] as? String, !text.isEmpty else { return nil }
            let firstLine = text.split(separator: "\n").first.map(String.init) ?? ""
            return "\(a?["locale"] as? String ?? "?"): \(firstLine.prefix(60))"
        }
        if !texts.isEmpty { notes = texts.joined(separator: " | ") }
    }
    print("build \(number)  \(state)\(expired)  uploaded \(uploaded)  \(notes)")
}
