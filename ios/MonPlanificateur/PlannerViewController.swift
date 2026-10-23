import UIKit
import WebKit
import UniformTypeIdentifiers
import UserNotifications
import Vision
import PDFKit
import ImageIO

final class PlannerViewController: UIViewController, WKNavigationDelegate, WKUIDelegate,
                                   WKScriptMessageHandler, UIDocumentPickerDelegate, UNUserNotificationCenterDelegate {
    private var webView: WKWebView!
    private var messageProxy: WeakScriptMessageHandler!
    private var pendingExport: Data?
    private var pageReady = false
    private var pendingScripts = [String]()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 247/255, green: 248/255, blue: 250/255, alpha: 1)
        configureNotifications()
        configureWebView()
        guard let page = Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "Web") else {
            showMessage("Fichier d’application manquant", "Réinstallez l’application iOS.")
            return
        }
        webView.loadFileURL(page, allowingReadAccessTo: page.deletingLastPathComponent())
    }

    private func configureWebView() {
        let controller = WKUserContentController()
        messageProxy = WeakScriptMessageHandler(target: self)
        ["scheduleAlarm", "scheduleAlarms", "cancelAlarm", "printPlanner", "exportBackup", "chooseBackupFile", "chooseScheduleFile", "restoreAlarms"]
            .forEach { controller.add(messageProxy, name: $0) }
        let shim = """
        (() => {
          const send = (name) => (...args) => window.webkit.messageHandlers[name].postMessage(args);
          window.Android = {
            scheduleAlarm: send('scheduleAlarm'), scheduleAlarms: send('scheduleAlarms'), cancelAlarm: send('cancelAlarm'), printPlanner: send('printPlanner'),
            exportBackup: send('exportBackup'), chooseBackupFile: send('chooseBackupFile'),
            chooseScheduleFile: send('chooseScheduleFile'), restoreAlarms: send('restoreAlarms')
          };
        })();
        """
        controller.addUserScript(WKUserScript(source: shim, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let config = WKWebViewConfiguration()
        config.userContentController = controller
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        webView = WKWebView(frame: .zero, configuration: config)
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.isOpaque = false
        webView.backgroundColor = view.backgroundColor
        webView.scrollView.contentInsetAdjustmentBehavior = .automatic
        webView.navigationDelegate = self
        webView.uiDelegate = self
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.topAnchor.constraint(equalTo: view.topAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
    }

    private func configureNotifications() {
        let done = UNNotificationAction(identifier: "PLANNER_DONE", title: "Fait", options: [.foreground])
        let snooze = UNNotificationAction(identifier: "PLANNER_SNOOZE", title: "+10 min", options: [])
        let category = UNNotificationCategory(identifier: "PLANNER_REMINDER", actions: [done, snooze], intentIdentifiers: [], options: [])
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        pageReady = true
        let queued = pendingScripts
        pendingScripts.removeAll()
        queued.forEach { webView.evaluateJavaScript($0, completionHandler: nil) }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .list])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let content = response.notification.request.content
        if response.actionIdentifier == "PLANNER_DONE",
           let date = content.userInfo["date"] as? String,
           let eventID = content.userInfo["eventId"] as? String {
            callJS("window.markEventDone && window.markEventDone(\(jsLiteral(date)),\(jsLiteral(eventID)))")
        } else if response.actionIdentifier == "PLANNER_SNOOZE" {
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 10 * 60, repeats: false)
            let request = UNNotificationRequest(identifier: response.notification.request.identifier + ".snooze." + UUID().uuidString,
                                                content: content, trigger: trigger)
            center.add(request, withCompletionHandler: nil)
        }
        completionHandler()
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let args = message.body as? [Any] else { return }
        switch message.name {
        case "scheduleAlarm":
            guard args.count >= 4, let date = args[0] as? String, let eventID = args[1] as? String,
                  let time = args[2] as? String, let title = args[3] as? String else { return }
            scheduleBatch([["date": date, "id": eventID, "time": time, "title": title,
                            "sound": args.count < 5 || (args[4] as? Bool ?? true)]])
        case "scheduleAlarms":
            guard let raw = args.first as? String, let data = raw.data(using: .utf8),
                  let rows = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return }
            scheduleBatch(rows)
        case "cancelAlarm":
            guard args.count >= 2, let date = args[0] as? String, let eventID = args[1] as? String else { return }
            UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [notificationID(date, eventID)])
        case "printPlanner":
            printPlanner()
        case "exportBackup":
            guard let json = args.first as? String else { return }
            pendingExport = json.data(using: .utf8)
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("mon-planificateur-sauvegarde.json")
            do {
                try pendingExport?.write(to: url, options: .atomic)
                let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
                picker.delegate = self
                present(picker, animated: true)
            } catch { showMessage("Export impossible", error.localizedDescription) }
        case "chooseBackupFile":
            presentPicker(types: [.json])
        case "chooseScheduleFile":
            presentPicker(types: [.image, .pdf, .plainText, .commaSeparatedText, .rtf, .spreadsheet, .content, .item])
        case "restoreAlarms":
            guard let raw = args.first as? String, let data = raw.data(using: .utf8),
                  let rows = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return }
            scheduleBatch(rows)
        default: break
        }
    }

    private func scheduleBatch(_ rows: [[String: Any]]) {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        let valid = rows.compactMap { row -> (String, String, String, String, Bool, Date)? in
            guard let date = row["date"] as? String, let id = row["id"] as? String,
                  let time = row["time"] as? String,
                  let when = formatter.date(from: "\(date) \(time)"), when > Date() else { return nil }
            return (date, id, time, row["title"] as? String ?? "Rappel", row["sound"] as? Bool ?? true, when)
        }
        guard !valid.isEmpty else { return }
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            guard granted else {
                DispatchQueue.main.async { self.showMessage("Notifications désactivées", "Autorisez les notifications dans Réglages pour recevoir vos rappels.") }
                return
            }
            for (date, id, time, title, sound, when) in valid {
                let content = UNMutableNotificationContent()
                content.title = title.isEmpty ? "Rappel du planificateur" : title
                content.body = "C’est le moment prévu dans votre journée."
                content.sound = sound ? .default : nil
                content.categoryIdentifier = "PLANNER_REMINDER"
                content.userInfo = ["date": date, "eventId": id]
                let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: when)
                let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
                center.removePendingNotificationRequests(withIdentifiers: [self.notificationID(date, id)])
                center.add(UNNotificationRequest(identifier: self.notificationID(date, id), content: content, trigger: trigger))
            }
        }
    }

    private func printPlanner() {
        let controller = UIPrintInteractionController.shared
        let info = UIPrintInfo(dictionary: nil)
        info.jobName = "Mon planificateur"
        info.outputType = .general
        controller.printInfo = info
        controller.printFormatter = webView.viewPrintFormatter()
        controller.present(animated: true, completionHandler: nil)
    }

    private func notificationID(_ date: String, _ eventID: String) -> String {
        "planner.\(date).\(eventID)".replacingOccurrences(of: " ", with: "_")
    }

    private func presentPicker(types: [UTType]) {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }
        if pendingExport != nil {
            pendingExport = nil
            callJS("window.onBackupExported && window.onBackupExported(true,'Sauvegarde enregistrée.')")
            return
        }
        let isAccessing = url.startAccessingSecurityScopedResource()
        defer { if isAccessing { url.stopAccessingSecurityScopedResource() } }
        do {
            if url.pathExtension.lowercased() == "json" {
                let text = try String(contentsOf: url, encoding: .utf8)
                guard let data = text.data(using: .utf8), (try JSONSerialization.jsonObject(with: data)) is [String: Any] else {
                    showMessage("Sauvegarde invalide", "Ce fichier ne contient pas de sauvegarde compatible."); return
                }
                callJS("window.onBackupRestored && window.onBackupRestored(\(text))")
                return
            }
            extractSchedule(from: url)
        } catch { showMessage("Lecture impossible", error.localizedDescription) }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        guard pendingExport != nil else { return }
        pendingExport = nil
        callJS("window.onBackupExported && window.onBackupExported(false,'Export annulé.')")
    }

    private func extractSchedule(from url: URL) {
        let ext = url.pathExtension.lowercased()
        if ["txt", "csv", "rtf"].contains(ext) {
            do {
                let data = try Data(contentsOf: url)
                let text: String
                if ext == "rtf" {
                    text = (try? NSAttributedString(data: data, options: [.documentType: NSAttributedString.DocumentType.rtf], documentAttributes: nil).string) ?? ""
                } else { text = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self) }
                deliverSchedule(text, source: url.lastPathComponent)
            } catch { showMessage("Lecture impossible", error.localizedDescription) }
            return
        }
        if ["doc", "docx", "xls", "xlsx"].contains(ext) {
            showMessage("Format non disponible sur iOS", "Pour l’import iOS, utilisez une image, un PDF, un fichier CSV, TXT ou RTF. Vous pouvez aussi convertir ce document en PDF.")
            return
        }
        if ext == "pdf" {
            guard let pdf = PDFDocument(url: url) else { showMessage("PDF illisible", "Ce fichier PDF ne peut pas être ouvert."); return }
            let embeddedText = (0..<pdf.pageCount).compactMap { pdf.page(at: $0)?.string }.joined(separator: "\n")
            if !parseSchedule(embeddedText).isEmpty {
                deliverSchedule(embeddedText, source: url.lastPathComponent)
                return
            }
            var pages: [CGImage] = []
            for index in 0..<min(pdf.pageCount, 20) {
                guard let page = pdf.page(at: index) else { continue }
                let bounds = page.bounds(for: .mediaBox)
                let scale = min(2.2, 1800 / max(bounds.width, bounds.height))
                let size = CGSize(width: bounds.width * scale, height: bounds.height * scale)
                let image = UIGraphicsImageRenderer(size: size).image { ctx in
                    UIColor.white.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
                    ctx.cgContext.translateBy(x: 0, y: size.height)
                    ctx.cgContext.scaleBy(x: scale, y: -scale)
                    page.draw(with: .mediaBox, to: ctx.cgContext)
                }
                if let cg = image.cgImage { pages.append(cg) }
            }
            recognize(images: pages, source: url.lastPathComponent)
            return
        }
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil), let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
            showMessage("Fichier non pris en charge", "Choisissez une image, un PDF, CSV, TXT ou RTF."); return
        }
        recognize(images: [image], source: url.lastPathComponent)
    }

    private func recognize(images: [CGImage], source: String) {
        guard !images.isEmpty else { showMessage("PDF vide", "Aucune page lisible n’a été trouvée."); return }
        DispatchQueue.global(qos: .userInitiated).async {
            var lines = [String]()
            for image in images {
                let request = VNRecognizeTextRequest()
                request.recognitionLevel = .accurate
                request.recognitionLanguages = ["fr-FR", "en-US"]
                request.usesLanguageCorrection = true
                do {
                    try VNImageRequestHandler(cgImage: image).perform([request])
                    lines += (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
                } catch { continue }
            }
            DispatchQueue.main.async { self.deliverSchedule(lines.joined(separator: "\n"), source: source) }
        }
    }

    private func deliverSchedule(_ text: String, source: String) {
        let items = parseSchedule(text)
        guard let data = try? JSONSerialization.data(withJSONObject: items, options: [.fragmentsAllowed]),
              let json = String(data: data, encoding: .utf8) else { showMessage("Import impossible", "Le texte n’a pas pu être converti."); return }
        let sourceJSON = (try? JSONSerialization.data(withJSONObject: source, options: [.fragmentsAllowed])).flatMap { String(data: $0, encoding: .utf8) } ?? "\"fichier\""
        callJS("window.onScheduleExtracted && window.onScheduleExtracted(\(json),\(sourceJSON))")
    }

    private func parseDocumentDate(_ line: String) -> String? {
        let patterns: [(String, String)] = [
            ("(?<!\\d)([0-3]?\\d)[/.-]([01]?\\d)[/.-](20\\d{2})(?!\\d)", "dmy"),
            ("(?<!\\d)(20\\d{2})[-/.]([01]?\\d)[-/.]([0-3]?\\d)(?!\\d)", "ymd"),
            ("(?<!\\d)([0-3]?\\d)(?:er)?\\s+(janvier|février|fevrier|mars|avril|mai|juin|juillet|août|aout|septembre|octobre|novembre|décembre|decembre)\\s+(20\\d{2})(?!\\d)", "french")
        ]
        for (pattern, kind) in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
                  let match = regex.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) else { continue }
            func part(_ index: Int) -> String? {
                guard let range = Range(match.range(at: index), in: line) else { return nil }
                return String(line[range])
            }
            var day: Int, month: Int, year: Int
            if kind == "dmy" {
                guard let d = part(1).flatMap(Int.init), let m = part(2).flatMap(Int.init), let y = part(3).flatMap(Int.init) else { continue }
                day = d; month = m; year = y
            } else if kind == "ymd" {
                guard let y = part(1).flatMap(Int.init), let m = part(2).flatMap(Int.init), let d = part(3).flatMap(Int.init) else { continue }
                day = d; month = m; year = y
            } else {
                guard let d = part(1).flatMap(Int.init), let y = part(3).flatMap(Int.init), let name = part(2)?.lowercased() else { continue }
                day = d; year = y
                if name.hasPrefix("janv") { month = 1 }
                else if name.hasPrefix("fév") || name.hasPrefix("fev") { month = 2 }
                else if name == "mars" { month = 3 }
                else if name == "avril" { month = 4 }
                else if name == "mai" { month = 5 }
                else if name == "juin" { month = 6 }
                else if name == "juillet" { month = 7 }
                else if name.hasPrefix("aoû") || name.hasPrefix("aou") { month = 8 }
                else if name == "septembre" { month = 9 }
                else if name == "octobre" { month = 10 }
                else if name == "novembre" { month = 11 }
                else if name.hasPrefix("déc") || name.hasPrefix("dec") { month = 12 }
                else { continue }
            }
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(secondsFromGMT: 0)!
            var components = DateComponents(); components.year = year; components.month = month; components.day = day
            if let date = calendar.date(from: components), calendar.component(.day, from: date) == day,
               calendar.component(.month, from: date) == month {
                return String(format: "%04d-%02d-%02d", year, month, day)
            }
        }
        return nil
    }

    private func cleanEventDescription(_ line: String) -> String {
        var value = line
        let patterns = [
            "(?<!\\d)([0-3]?\\d)[/.-]([01]?\\d)[/.-](20\\d{2})(?!\\d)",
            "(?<!\\d)(20\\d{2})[-/.]([01]?\\d)[-/.]([0-3]?\\d)(?!\\d)",
            "(?i)(?<!\\d)([0-3]?\\d)(?:er)?\\s+(janvier|février|fevrier|mars|avril|mai|juin|juillet|août|aout|septembre|octobre|novembre|décembre|decembre)\\s+(20\\d{2})(?!\\d)",
            "(?i)(?<![\\p{L}])(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|mon|tue|wed|thu|fri|sat|sun|pdf|cycle|source|poste|horaires|appliqués)(?![\\p{L}])",
            "(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)\\s*[-–—]\\s*([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)",
            "(?i)(?<!\\d)([01]?\\d|2[0-3])\\s*(?:h|:|\\.)(\\s*[0-5]?\\d)?(?!\\d)",
            "\\b[A-Z]{1,5}\\b",
            "(?i)\\b(?:pdf|cycle|source|poste|horaires|appliqués)\\b"
        ]
        for pattern in patterns {
            value = value.replacingOccurrences(of: pattern, with: " ", options: .regularExpression)
        }
        value = value.replacingOccurrences(of: "[\\p{Punct}\\s]+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if value.range(of: "^[\\p{L}]{1,6}$", options: .regularExpression) != nil { return "" }
        return value
    }

    private func cleanDateOnlyDescription(_ line: String) -> String {
        line.replacingOccurrences(of: "(?<!\\d)([0-3]?\\d)[/.-]([01]?\\d)[/.-](20\\d{2})(?!\\d)", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "(?<!\\d)(20\\d{2})[-/.]([01]?\\d)[-/.]([0-3]?\\d)(?!\\d)", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "(?i)(?<!\\d)([0-3]?\\d)(?:er)?\\s+(?:janvier|février|fevrier|mars|avril|mai|juin|juillet|août|aout|septembre|octobre|novembre|décembre|decembre)\\s+20\\d{2}(?!\\d)", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "(?i)(?<![\\p{L}])(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|mon|tue|wed|thu|fri|sat|sun)(?![\\p{L}])", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "[\\p{Punct}\\s]+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func parseSchedule(_ text: String) -> [[String: Any]] {
        guard let rangeRegex = try? NSRegularExpression(pattern: "(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)\\s*[-–—]\\s*([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)"),
              let timeRegex = try? NSRegularExpression(pattern: "(?<!\\d)([01]?\\d|2[0-3])\\s*(?:h|:|\\.)(\\s*[0-5]?\\d)?(?!\\d)", options: [.caseInsensitive]) else { return [] }
        let normalized = text.replacingOccurrences(of: "\u{200B}", with: " ")
            .replacingOccurrences(of: "\u{FEFF}", with: " ")
            .replacingOccurrences(of: "(?m)(\\d{1,2}:\\d{2})\\s*\\n\\s*[-–—]\\s*(\\d{1,2}:\\d{2})", with: "$1-$2", options: .regularExpression)
        let lines = normalized.replacingOccurrences(of: "\\r", with: "\\n").components(separatedBy: .newlines)
        let documentHasDates = lines.contains { parseDocumentDate($0) != nil }
        var currentDate: String?
        var explicitDates = [String]()
        var importedDates = Set<String>()
        var result = [[String: Any]]()
        for lineSub in lines {
            let line = lineSub.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty else { continue }
            if let date = parseDocumentDate(line) { currentDate = date; if !explicitDates.contains(date) { explicitDates.append(date) } }
            let timeLine = line
                .replacingOccurrences(of: "(?<!\\d)([0-3]?\\d)[/.-]([01]?\\d)[/.-](20\\d{2})(?!\\d)", with: " ", options: .regularExpression)
                .replacingOccurrences(of: "(?<!\\d)(20\\d{2})[-/.]([01]?\\d)[-/.]([0-3]?\\d)(?!\\d)", with: " ", options: .regularExpression)
            let ns = timeLine as NSString
            let full = NSRange(location: 0, length: ns.length)
            var slots = [(start: String, end: String?)]()
            let ranges = rangeRegex.matches(in: timeLine, range: full)
            if !ranges.isEmpty {
                for match in ranges {
                    guard let h = Int(ns.substring(with: match.range(at: 1))),
                          let m = Int(ns.substring(with: match.range(at: 2))),
                          let eh = Int(ns.substring(with: match.range(at: 3))),
                          let em = Int(ns.substring(with: match.range(at: 4))) else { continue }
                    slots.append((String(format: "%02d:%02d", h, m), String(format: "%02d:%02d", eh, em)))
                }
            } else {
                for match in timeRegex.matches(in: timeLine, range: full) {
                    guard let h = Int(ns.substring(with: match.range(at: 1))) else { continue }
                    let minute = match.range(at: 2).location == NSNotFound ? 0 : (Int(ns.substring(with: match.range(at: 2)).trimmingCharacters(in: .whitespaces)) ?? 0)
                    slots.append((String(format: "%02d:%02d", h, minute), nil))
                }
            }
            if slots.isEmpty {
                if let currentDate {
                    let description = cleanDateOnlyDescription(line)
                    if !description.isEmpty {
                        result.append(["date": currentDate, "time": "", "text": description])
                        importedDates.insert(currentDate)
                    }
                }
                continue
            }
            if documentHasDates && currentDate == nil { continue }
            let description = cleanEventDescription(line)
            for slot in slots {
                let title = description.isEmpty ? "Activité importée\(slot.end.map { " (\(slot.start)–\($0))" } ?? "")" : description
                let pieces = slot.start.split(separator: ":").compactMap { Int($0) }
                guard pieces.count == 2 else { continue }
                var item: [String: Any] = ["hour": pieces[0], "minute": pieces[1], "time": slot.start, "text": title]
                if let currentDate { item["date"] = currentDate; importedDates.insert(currentDate) }
                result.append(item)
            }
        }
        for date in explicitDates where !importedDates.contains(date) {
            result.append(["date": date, "time": "", "text": "Journée repérée dans le document"])
        }
        return result
    }

    private func jsLiteral(_ value: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]),
              let text = String(data: data, encoding: .utf8) else { return "\"\"" }
        return text
    }

    private func callJS(_ script: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            guard self.pageReady else { self.pendingScripts.append(script); return }
            self.webView.evaluateJavaScript(script, completionHandler: nil)
        }
    }

    private func showMessage(_ title: String, _ message: String) {
        guard isViewLoaded, view.window != nil else { return }
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }
}

private final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(target: WKScriptMessageHandler) { self.target = target }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(userContentController, didReceive: message)
    }
}
