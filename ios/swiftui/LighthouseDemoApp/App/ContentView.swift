import LighthouseSDK
import SwiftUI

struct ContentView: View {
    @ObservedObject var model: DemoAppModel

    private var isPreview: Bool {
        ProcessInfo.processInfo.environment["XCODE_RUNNING_FOR_PREVIEWS"] == "1"
    }

    var body: some View {
        NavigationStack {
            ZStack {
                backgroundGradient

                VStack(spacing: 16) {
                    statusCard
                    filterBar
                    actionsRow
                    messageContent
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 24)
            }
            .navigationTitle("Messages")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        model.refreshAllInBackground()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }

                    Button {
                        model.isCredentialsSheetPresented = true
                    } label: {
                        Image(systemName: "slider.horizontal.3")
                    }
                }
            }
        }
        .sheet(isPresented: $model.isCredentialsSheetPresented) {
            CredentialsSheet(model: model)
                .presentationDetents([PresentationDetent.medium])
        }
        .task {
            guard !isPreview else { return }
            model.startIfPossible()
        }
    }

    private var statusCard: some View {
        HStack(spacing: 12) {
            Image(systemName: model.status.symbolName)
                .foregroundStyle(statusColor)
            VStack(alignment: .leading, spacing: 4) {
                Text(model.status.label)
                    .font(.headline)
                    .foregroundStyle(.white)
                if !model.errorDetail.isEmpty {
                    Text(model.errorDetail)
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.72))
                }
            }
            Spacer()
            if model.unreadCount > 0 {
                Text(model.unreadCount > 99 ? "99+" : "\(model.unreadCount)")
                    .font(.caption.bold())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color.red)
                    .clipShape(Capsule())
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color.white.opacity(0.08))
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(Color.white.opacity(0.08), lineWidth: 1)
                )
        )
    }

    private var filterBar: some View {
        Picker("Filter", selection: $model.selectedFilter) {
            ForEach(DemoAppModel.Filter.allCases) { filter in
                Text(filter.title).tag(filter)
            }
        }
        .pickerStyle(.segmented)
        .colorScheme(.dark)
        .onChange(of: model.selectedFilter) { _ in
            model.reloadCurrentFilter()
        }
    }

    private var actionsRow: some View {
        Button {
            model.markAllRead()
        } label: {
            HStack {
                Text("Mark all as read")
                    .foregroundStyle(.white)
                Spacer()
                if model.isMarkingAllRead {
                    ProgressView()
                }
            }
            .padding()
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color.white.opacity(0.05))
            )
        }
        .buttonStyle(.plain)
        .disabled(model.isMarkingAllRead)
    }

    @ViewBuilder
    private var messageContent: some View {
        if model.isLoadingMessages && model.messages.isEmpty {
            VStack(spacing: 14) {
                Spacer()
                ProgressView("Loading messages...")
                    .foregroundStyle(.white)
                Spacer()
            }
        } else if model.messages.isEmpty {
            EmptyStateView(
                title: "No messages",
                systemImage: "bell.slash",
                description: model.errorDetail.isEmpty ? "Nothing in this filter right now." : model.errorDetail
            )
        } else {
            ScrollView(showsIndicators: false) {
                LazyVStack(spacing: 14) {
                    ForEach(model.messages, id: \.customerMessageId) { message in
                        MessageRow(
                            message: message,
                            onRead: { model.markMessageRead(message.customerMessageId) },
                            onArchive: { model.archiveMessage(message.customerMessageId) },
                            onRestore: { model.restoreMessage(message.customerMessageId) },
                            onEngage: { identifier in
                                model.engageCallToAction(messageId: message.customerMessageId, identifier: identifier)
                            }
                        )
                    }

                    if model.hasNextPage {
                        Button {
                            model.loadNextPageIfNeeded()
                        } label: {
                            HStack {
                                Spacer()
                                if model.isLoadingMore {
                                    ProgressView()
                                } else {
                                    Text("Load more")
                                        .fontWeight(.semibold)
                                        .foregroundStyle(.white)
                                }
                                Spacer()
                            }
                            .padding(.vertical, 8)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.bottom, 16)
            }
        }
    }

    private var backgroundGradient: some View {
        LinearGradient(
            colors: [
                Color(red: 0.07, green: 0.09, blue: 0.14),
                Color(red: 0.14, green: 0.10, blue: 0.20),
                Color(red: 0.08, green: 0.12, blue: 0.18),
            ],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()
    }

    private var statusColor: Color {
        switch model.status {
        case .connected: .green
        case .connecting: .orange
        case .expired, .error: .red
        case .idle: .secondary
        }
    }
}

private struct EmptyStateView: View {
    let title: String
    let systemImage: String
    let description: String

    var body: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: systemImage)
                .font(.system(size: 32, weight: .medium))
                .foregroundStyle(.white.opacity(0.7))
            Text(title)
                .font(.headline)
                .foregroundStyle(.white)
            Text(description)
                .font(.footnote)
                .foregroundStyle(.white.opacity(0.72))
                .multilineTextAlignment(.center)
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
    }
}

private struct CredentialsSheet: View {
    @ObservedObject var model: DemoAppModel
    @SwiftUI.Environment(\.dismiss) private var dismiss

    @State private var tenantKey = ""
    @State private var playerId = ""
    @State private var environment: LighthouseSDK.Environment = .prod

    var body: some View {
        NavigationStack {
            Form {
                Section("Dev Setup") {
                    TextField("Tenant Key", text: $tenantKey, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    TextField("Player ID", text: $playerId, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    Picker("Environment", selection: $environment) {
                        Text("pre-dev").tag(LighthouseSDK.Environment.preDev)
                        Text("dev").tag(LighthouseSDK.Environment.dev)
                        Text("stage").tag(LighthouseSDK.Environment.stage)
                        Text("prod").tag(LighthouseSDK.Environment.prod)
                    }
                }
            }
            .navigationTitle("Connect Session")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Connect") {
                        model.credentials = .init(
                            tenantKey: tenantKey,
                            playerId: playerId,
                            environment: environment
                        )
                        model.connect()
                        dismiss()
                    }
                    .disabled(tenantKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                              playerId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear {
                tenantKey = model.credentials.tenantKey
                playerId = model.credentials.playerId
                environment = model.credentials.environment
            }
        }
    }
}

private struct MessageRow: View {
    let message: CustomerMessage
    let onRead: () -> Void
    let onArchive: () -> Void
    let onRestore: () -> Void
    let onEngage: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(message.message.title)
                        .font(.headline)
                        .foregroundStyle(.white)
                    Text(message.message.category)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.cyan)
                }
                Spacer()
                statusBadges
            }

            Text(message.message.body.htmlStripped)
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.88))

            HStack {
                Text("Created \(message.createDate)")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.45))
                Spacer()
                if message.readDate == nil {
                    Button("Mark read", action: onRead)
                        .buttonStyle(.bordered)
                }
                if message.archivedDate == nil {
                    Button("Archive", action: onArchive)
                        .buttonStyle(.borderedProminent)
                        .tint(.orange)
                } else {
                    Button("Restore", action: onRestore)
                        .buttonStyle(.borderedProminent)
                        .tint(.green)
                }
            }

            if !message.callToActions.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(message.callToActions, id: \.identifier) { action in
                            Button(action.translation) {
                                onEngage(action.identifier)
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color.white.opacity(0.08))
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(Color.white.opacity(0.08), lineWidth: 1)
                )
        )
    }

    private var statusBadges: some View {
        VStack(alignment: .trailing, spacing: 6) {
            if message.readDate == nil {
                badge("Unread", color: .blue)
            }
            if message.archivedDate != nil {
                badge("Archived", color: .orange)
            }
        }
    }

    private func badge(_ title: String, color: Color) -> some View {
        Text(title)
            .font(.caption2.bold())
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.18))
            .foregroundStyle(color)
            .clipShape(Capsule())
    }
}

private extension String {
    var htmlStripped: String {
        guard let data = data(using: .utf8),
              let attributed = try? NSAttributedString(
                data: data,
                options: [
                    .documentType: NSAttributedString.DocumentType.html,
                    .characterEncoding: String.Encoding.utf8.rawValue,
                ],
                documentAttributes: nil
              ) else {
            return self
        }

        return attributed.string
            .replacingOccurrences(of: "\n\n", with: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

#Preview("Loaded Messages") {
    ContentView(model: .previewLoaded)
        .preferredColorScheme(.dark)
}

#Preview("Empty State") {
    ContentView(model: .previewEmpty)
        .preferredColorScheme(.dark)
}
