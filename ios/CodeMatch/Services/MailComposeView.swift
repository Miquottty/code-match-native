import MessageUI
import SwiftUI

/// 添付つきのメール作成画面（Apple の「メール」）。レポートPDFと診断ログ（#143）で使う。
///
/// 宛先・件名・本文を埋めた状態で開き、送信は操作者が行う。「メール」にアカウントがなく
/// `canSendMail` が false の端末では呼び出し側が共有シートへ切り替える。
struct MailComposeView: UIViewControllerRepresentable {
    struct Attachment {
        let data: Data
        let fileName: String
        var mimeType = "application/pdf"
    }

    let recipients: [String]
    let subject: String
    let body: String
    let attachment: Attachment
    let onFinish: () -> Void

    init(
        recipients: [String],
        subject: String,
        body: String,
        attachment: Attachment,
        onFinish: @escaping () -> Void
    ) {
        self.recipients = recipients
        self.subject = subject
        self.body = body
        self.attachment = attachment
        self.onFinish = onFinish
    }

    init(content: ReportMailContent, attachment: Attachment, onFinish: @escaping () -> Void) {
        self.init(
            recipients: ReportMailContent.recipients,
            subject: content.subject,
            body: content.body,
            attachment: attachment,
            onFinish: onFinish
        )
    }

    static var canSendMail: Bool {
        MFMailComposeViewController.canSendMail()
    }

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let controller = MFMailComposeViewController()
        controller.mailComposeDelegate = context.coordinator
        controller.setToRecipients(recipients)
        controller.setSubject(subject)
        controller.setMessageBody(body, isHTML: false)
        controller.addAttachmentData(attachment.data, mimeType: attachment.mimeType, fileName: attachment.fileName)
        return controller
    }

    func updateUIViewController(_ uiViewController: MFMailComposeViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onFinish: onFinish)
    }

    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        private let onFinish: () -> Void

        init(onFinish: @escaping () -> Void) {
            self.onFinish = onFinish
        }

        func mailComposeController(
            _ controller: MFMailComposeViewController,
            didFinishWith result: MFMailComposeResult,
            error: Error?
        ) {
            // 送信・下書き保存・取り消しのいずれでも画面を閉じるだけ。結果はアプリに残さない。
            onFinish()
        }
    }
}
