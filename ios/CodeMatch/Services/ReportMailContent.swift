import Foundation

/// セッション詳細から書き出す2種類のPDF。
enum ReportKind {
    case matchHistory
    case inspection
}

/// レポートPDFをメールで共有するときの宛先・件名・本文。
///
/// 宛先は当面固定で、レポートは操作者自身の受信箱にだけ送る。本文にはレポートの見出し
/// （セッション、仕向地、箱数・行数）を繰り返し、添付を開かなくても内容が分かるようにする。
/// 送信そのものは操作者が使うメールアプリに任せる。Android の `ReportMailContent` と同じ規則。
struct ReportMailContent: Equatable {
    /// すべてのレポートメールの宛先。
    static let recipients = ["takemoto1075@icloud.com"]

    let subject: String
    let body: String

    static func make(session: MatchSession, kind: ReportKind, fileName: String, locale: Locale) -> ReportMailContent {
        let appLanguage = AppLanguage(locale)
        let title: String
        switch kind {
        case .matchHistory: title = AppLocalization.string("照合履歴レポート")
        case .inspection: title = AppLocalization.string("検品レポート")
        }
        let destination = session.resolvedDestination
        // 「検品レポート 2026/09/07 23:17 - 澤井製作所」。セッション名ではなく開始日時で並ぶようにする。
        var subject = "\(title) \(appLanguage.formatDateTime(session.startedAt))"
        if let destination {
            subject += " - \(destination.displayName)"
        }

        var lines: [String] = []
        switch kind {
        case .matchHistory: lines.append(AppLocalization.string("照合履歴レポートをお送りします。"))
        case .inspection: lines.append(AppLocalization.string("検品レポートをお送りします。"))
        }
        lines.append("")
        lines.append(AppLocalization.string("■ セッション"))
        if !session.displayName.isEmpty {
            lines.append("\(AppLocalization.string("セッション名")): \(session.displayName)")
        }
        lines.append("\(AppLocalization.string("開始")): \(appLanguage.formatDateTime(session.startedAt))")
        if let endedAt = session.endedAt {
            lines.append("\(AppLocalization.string("終了")): \(appLanguage.formatDateTime(endedAt))")
        } else {
            lines.append("\(AppLocalization.string("状態")): \(AppLocalization.string("照合中"))")
        }
        if let destination {
            lines.append(AppLocalization.string("仕向地: \(destination.displayName)"))
        }
        lines.append(AppLocalization.string("検査箱数: \(session.matchedCount)箱"))
        lines.append(contentsOf: countLines(session: session, kind: kind, destination: destination))
        lines.append("")
        lines.append(AppLocalization.string("■ 添付"))
        lines.append(fileName)

        return ReportMailContent(subject: subject, body: lines.joined(separator: "\n"))
    }

    /// レポート自身の見出しと同じ件数の行。
    private static func countLines(session: MatchSession, kind: ReportKind, destination: Destination?) -> [String] {
        switch kind {
        case .inspection:
            let report = InspectionReport.make(session: session)
            switch report.layout {
            case .sawai: return [AppLocalization.string("品番数（枝番別）: \(report.rowCount)")]
            case .molten: return [AppLocalization.string("納品番号数: \(report.rowCount)")]
            case .denso: return [AppLocalization.string("品番数: \(report.rowCount)")]
            }
        case .matchHistory:
            var lines = [AppLocalization.string("品番数: \(session.groupedEntries.count)")]
            if destination == .molten {
                lines.append(AppLocalization.string("納品番号数: \(session.deliveryNumberCount)"))
            }
            return lines
        }
    }
}

/// 診断ログを管理者へ送るメールの宛先・件名・本文・添付名（#143）。
///
/// 自動送信はしない。宛先入りのメール作成画面を開き、送信は操作者が行う。
/// 診断ログは読み取った値を含まないため、照合ログ（生の値を含む）は添付しない。
struct DiagnosticsMailContent: Equatable {
    /// 診断ログの送り先。レポートの宛先（`ReportMailContent.recipients`）とは別に管理する。
    static let recipients = ["contact-codematch@googlegroups.com"]

    let subject: String
    let body: String
    let fileName: String

    static func make(
        appVersion: String,
        device: String,
        connectionStatus: String,
        eventCount: Int,
        pendingRestoreCount: Int,
        generatedAt: Date,
        timeZone: TimeZone = .current
    ) -> DiagnosticsMailContent {
        let stamp = DateFormatter()
        stamp.calendar = Calendar(identifier: .gregorian)
        stamp.locale = Locale(identifier: "en_US_POSIX")
        stamp.timeZone = timeZone
        stamp.dateFormat = "yyyy/MM/dd HH:mm"
        let fileStamp = DateFormatter()
        fileStamp.calendar = Calendar(identifier: .gregorian)
        fileStamp.locale = Locale(identifier: "en_US_POSIX")
        fileStamp.timeZone = timeZone
        fileStamp.dateFormat = "yyyyMMdd-HHmm"

        let fileName = "codematch-bluetooth-diagnostics-\(fileStamp.string(from: generatedAt)).txt"
        let subject = AppLocalization.string("CodeMatch 診断ログ")
            + " \(appVersion) \(device) \(stamp.string(from: generatedAt))"
        let lines = [
            AppLocalization.string("CodeMatch の Bluetooth 診断ログをお送りします。"),
            "",
            AppLocalization.string("アプリ: \(appVersion)"),
            AppLocalization.string("端末: \(device)"),
            AppLocalization.string("接続: \(connectionStatus)"),
            AppLocalization.string("記録: \(eventCount)件"),
            AppLocalization.string("復元待ちのスキャナ: \(pendingRestoreCount)台"),
            "",
            AppLocalization.string("■ 添付"),
            fileName,
            "",
            AppLocalization.string("読み取ったコードの内容は含まれていません。"),
        ]
        return DiagnosticsMailContent(
            subject: subject,
            body: lines.joined(separator: "\n"),
            fileName: fileName
        )
    }
}
