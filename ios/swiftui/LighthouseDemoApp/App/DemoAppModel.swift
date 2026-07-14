import Foundation
import LighthouseSDK

@MainActor
final class DemoAppModel: ObservableObject {
    enum Filter: String, CaseIterable, Identifiable {
        case unread
        case active
        case archived
        case all

        var id: String { rawValue }

        var title: String {
            switch self {
            case .active: "Active"
            case .unread: "Unread"
            case .archived: "Archived"
            case .all: "All"
            }
        }
    }

    enum Status {
        case idle
        case connecting
        case connected
        case expired
        case error(String)

        var label: String {
            switch self {
            case .idle: "Disconnected"
            case .connecting: "Connecting..."
            case .connected: "Connected"
            case .expired: "Session expired"
            case .error: "Connection failed"
            }
        }

        var symbolName: String {
            switch self {
            case .idle: "bolt.slash"
            case .connecting: "arrow.triangle.2.circlepath"
            case .connected: "checkmark.circle.fill"
            case .expired: "clock.badge.exclamationmark"
            case .error: "xmark.octagon.fill"
            }
        }
    }

    struct Credentials: Equatable {
        var tenantKey: String
        var playerId: String
        var environment: LighthouseSDK.Environment

        var isValid: Bool {
            !tenantKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !playerId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    private struct RefreshTokenResponse: Decodable {
        let refreshToken: String
    }

    private static let environmentBaseURLs: [LighthouseSDK.Environment: URL] = [
        .preDev: URL(string: "https://v3.mentor-pre-dev.neccton.ai/pic")!,
        .dev: URL(string: "https://v3.mentor-dev.neccton.ai/pic")!,
        .stage: URL(string: "https://v3.mentor-stage.neccton.ai/pic")!,
        .prod: URL(string: "https://v3.mentor.neccton.ai/pic")!,
    ]

    private enum DefaultsKey {
        static let tenantKey = "lh_demo_tenant_key"
        static let playerId = "lh_demo_player_id"
        static let environment = "lh_demo_environment"
    }

    private let defaultTenantKey = ""
    private let defaultPlayerId = ""

    @Published var credentials: Credentials
    @Published var isCredentialsSheetPresented = false
    @Published var status: Status = .idle
    @Published var selectedFilter: Filter = .unread
    @Published var messages: [CustomerMessage] = []
    @Published var unreadCount = 0
    @Published var isLoadingMessages = false
    @Published var isLoadingMore = false
    @Published var isMarkingAllRead = false
    @Published var errorDetail = ""

    private(set) var hasNextPage = false
    private(set) var currentPage = 0

    private let userDefaults: UserDefaults
    private var session: LighthouseSession?
    private var reconnectTask: Task<Void, Never>?
    private var loadSequence = 0

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
        let environment = LighthouseSDK.Environment(rawValue: userDefaults.string(forKey: DefaultsKey.environment) ?? "") ?? .prod
        self.credentials = Credentials(
            tenantKey: userDefaults.string(forKey: DefaultsKey.tenantKey) ?? defaultTenantKey,
            playerId: userDefaults.string(forKey: DefaultsKey.playerId) ?? defaultPlayerId,
            environment: environment
        )
        self.isCredentialsSheetPresented = !credentials.isValid
    }

    func startIfPossible() {
        guard session == nil, credentials.isValid else {
            if !credentials.isValid {
                isCredentialsSheetPresented = true
            }
            return
        }
        connect()
    }

    func connect() {
        guard credentials.isValid else {
            errorDetail = "Tenant key and player ID are required."
            isCredentialsSheetPresented = true
            return
        }

        reconnectTask?.cancel()
        status = .connecting
        errorDetail = ""
        messages = []
        unreadCount = 0
        hasNextPage = false
        currentPage = 0
        loadSequence += 1

        let creds = credentials
        persist(credentials: creds)

        Task {
            do {
                let refreshToken = try await fetchRefreshToken(credentials: creds)
                let newSession = try await LighthouseSession.initialize(
                    refreshToken: refreshToken,
                    options: InitializeOptions(
                        env: creds.environment,
                        language: "de",
                        country: "ZZ"
                    ),
                    onRefreshTokenExpired: { [weak self] in
                        Task { @MainActor [weak self] in
                            await self?.handleRefreshTokenExpired()
                        }
                    }
                )

                session = newSession
                status = .connected
                isCredentialsSheetPresented = false
                await refreshAll()
            } catch {
                session = nil
                messages = []
                unreadCount = 0
                status = .error(error.localizedDescription)
                errorDetail = error.localizedDescription
            }
        }
    }

    func refreshAll() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { await self.loadMessages(reset: true) }
            group.addTask { await self.refreshUnreadBadge() }
        }
    }

    func refreshAllInBackground() {
        Task { await refreshAll() }
    }

    func reloadCurrentFilter() {
        Task { await loadMessages(reset: true) }
    }

    func loadNextPageIfNeeded() {
        guard hasNextPage, !isLoadingMore else { return }
        Task { await loadMessages(reset: false) }
    }

    func markMessageRead(_ messageId: String) {
        Task {
            guard let session else { return }
            do {
                try await session.markMessageRead(messageId)
                applyReadLocally(messageId)
                await refreshUnreadBadge()
            } catch {
                apply(error)
            }
        }
    }

    func archiveMessage(_ messageId: String) {
        Task {
            guard let session else { return }
            do {
                try await session.archiveMessage(messageId)
                applyArchiveLocally(messageId)
                await refreshUnreadBadge()
            } catch {
                apply(error)
            }
        }
    }

    func restoreMessage(_ messageId: String) {
        Task {
            guard let session else { return }
            do {
                try await session.restoreMessage(messageId)
                applyRestoreLocally(messageId)
                await refreshUnreadBadge()
            } catch {
                apply(error)
            }
        }
    }

    func markAllRead() {
        guard !isMarkingAllRead else { return }
        isMarkingAllRead = true

        Task {
            defer { isMarkingAllRead = false }
            guard let session else { return }
            do {
                _ = try await session.markAllMessagesRead()
                await refreshAll()
            } catch {
                apply(error)
            }
        }
    }

    func engageCallToAction(messageId: String, identifier: String) {
        Task {
            guard let session else { return }
            do {
                try await session.engageCallToAction(messageId, callToActionIdentifier: identifier)
            } catch {
                apply(error)
            }
        }
    }

    private func handleRefreshTokenExpired() async {
        status = .expired
        errorDetail = "Refresh token expired. Reconnecting automatically..."
        session = nil

        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            self?.reconnectAfterExpiry()
        }
    }

    private func reconnectAfterExpiry() {
        connect()
    }

    private func persist(credentials: Credentials) {
        userDefaults.set(credentials.tenantKey, forKey: DefaultsKey.tenantKey)
        userDefaults.set(credentials.playerId, forKey: DefaultsKey.playerId)
        userDefaults.set(credentials.environment.rawValue, forKey: DefaultsKey.environment)
    }

    private func fetchRefreshToken(credentials: Credentials) async throws -> String {
        guard let baseURL = Self.environmentBaseURLs[credentials.environment] else {
            throw NSError(domain: "LighthouseDemo", code: -1, userInfo: [
                NSLocalizedDescriptionKey: "Unsupported environment."
            ])
        }

        var request = URLRequest(url: baseURL.appending(path: "/api/v1/authentication/refreshToken"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "apiKey": credentials.tenantKey,
            "customerId": credentials.playerId,
        ])

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        guard (200..<300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8) ?? HTTPURLResponse.localizedString(forStatusCode: http.statusCode)
            throw NSError(domain: "LighthouseDemo", code: http.statusCode, userInfo: [
                NSLocalizedDescriptionKey: "Refresh token request failed: \(message)"
            ])
        }

        return try JSONDecoder().decode(RefreshTokenResponse.self, from: data).refreshToken
    }

    private func refreshUnreadBadge() async {
        guard let session else { return }
        do {
            let result = try await session.unreadMessages(options: MessageListOptions(page: 0, limit: 1))
            unreadCount = result.totalItems
        } catch {
            unreadCount = 0
        }
    }

    private func loadMessages(reset: Bool) async {
        guard let session else { return }

        let sequence = loadSequence + 1
        loadSequence = sequence

        if reset {
            isLoadingMessages = true
            messages = []
            currentPage = 0
            hasNextPage = false
        } else {
            isLoadingMore = true
        }

        let page = reset ? 0 : currentPage + 1

        defer {
            isLoadingMessages = false
            isLoadingMore = false
        }

        do {
            let options = MessageListOptions(page: page, limit: 10)
            let result: PageResponse<CustomerMessage>
            switch selectedFilter {
            case .active:
                result = try await session.activeMessages(options: options)
            case .unread:
                result = try await session.unreadMessages(options: options)
            case .archived:
                result = try await session.archivedMessages(options: options)
            case .all:
                result = try await session.messages(options: options)
            }

            guard sequence == loadSequence else { return }

            if reset {
                messages = result.data
            } else {
                messages += result.data
            }
            currentPage = result.currentPage
            hasNextPage = result.hasNext
        } catch {
            apply(error)
        }
    }

    private func apply(_ error: Error) {
        if let apiError = error as? APIError {
            errorDetail = "HTTP \(apiError.status): \(apiError.message)"
        } else {
            errorDetail = error.localizedDescription
        }
        if case .expired = status {
            return
        }
        status = .error(errorDetail)
    }

    private func applyReadLocally(_ messageId: String) {
        switch selectedFilter {
        case .unread:
            messages.removeAll { $0.customerMessageId == messageId }
        case .active, .archived, .all:
            break
        }
    }

    private func applyArchiveLocally(_ messageId: String) {
        switch selectedFilter {
        case .active, .unread:
            messages.removeAll { $0.customerMessageId == messageId }
        case .archived, .all:
            break
        }
    }

    private func applyRestoreLocally(_ messageId: String) {
        switch selectedFilter {
        case .archived:
            messages.removeAll { $0.customerMessageId == messageId }
        case .active, .unread, .all:
            break
        }
    }
}

#if DEBUG
extension DemoAppModel {
    static var previewLoaded: DemoAppModel {
        let model = DemoAppModel(userDefaults: UserDefaults(suiteName: "LighthouseDemoApp.preview.loaded") ?? .standard)
        model.status = .connected
        model.errorDetail = ""
        model.unreadCount = 3
        model.selectedFilter = .unread
        model.messages = [
            previewMessage(
                customerMessageId: "preview-1",
                identifier: "message-1",
                category: "HEADS_UP",
                language: "en",
                country: "AT",
                title: "Your bonus is waiting",
                body: "<p>Open the lobby and claim your reward before it expires.</p>",
                actions: [
                    ["identifier": "open-lobby", "translation": "Open lobby"],
                    ["identifier": "claim", "translation": "Claim reward"],
                ],
                readDate: nil,
                archivedDate: nil,
                createDate: "2026-07-14T09:00:00Z"
            ),
            previewMessage(
                customerMessageId: "preview-2",
                identifier: "message-2",
                category: "TIP",
                language: "en",
                country: "AT",
                title: "Try the new missions",
                body: "<p>Finish today’s mission set to unlock an extra spin.</p>",
                actions: [
                    ["identifier": "view-missions", "translation": "View missions"],
                ],
                readDate: nil,
                archivedDate: nil,
                createDate: "2026-07-14T08:30:00Z"
            ),
        ]
        return model
    }

    static var previewEmpty: DemoAppModel {
        let model = DemoAppModel(userDefaults: UserDefaults(suiteName: "LighthouseDemoApp.preview.empty") ?? .standard)
        model.status = .connected
        model.errorDetail = ""
        model.unreadCount = 0
        model.selectedFilter = .archived
        model.messages = []
        return model
    }

    private static func previewMessage(
        customerMessageId: String,
        identifier: String,
        category: String,
        language: String,
        country: String,
        title: String,
        body: String,
        actions: [[String: String]],
        readDate: String?,
        archivedDate: String?,
        createDate: String
    ) -> CustomerMessage {
        let payload: [String: Any?] = [
            "customerMessageId": customerMessageId,
            "message": [
                "identifier": identifier,
                "category": category,
                "language": language,
                "country": country,
                "title": title,
                "body": body,
            ],
            "callToActions": actions,
            "readDate": readDate,
            "archivedDate": archivedDate,
            "createDate": createDate,
        ]

        let data = try! JSONSerialization.data(withJSONObject: payload)
        return try! JSONDecoder().decode(CustomerMessage.self, from: data)
    }
}
#endif
