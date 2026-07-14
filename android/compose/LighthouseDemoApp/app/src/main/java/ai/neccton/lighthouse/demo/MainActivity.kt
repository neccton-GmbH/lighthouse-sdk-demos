package ai.neccton.lighthouse.demo

import ai.neccton.lighthouse.CustomerMessage
import ai.neccton.lighthouse.Environment
import ai.neccton.lighthouse.InitializeOptions
import ai.neccton.lighthouse.LighthouseSession
import ai.neccton.lighthouse.MessageListOptions
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

private val AppBackground = listOf(
    Color(0xFF0B1220),
    Color(0xFF17142B),
    Color(0xFF0A1722),
)

private val Glass = Color.White.copy(alpha = 0.08f)
private val GlassStrong = Color.White.copy(alpha = 0.12f)
private val GlassBorder = Color.White.copy(alpha = 0.10f)
private val Accent = Color(0xFF79E4C2)
private val AccentMuted = Color(0xFF90A8FF)
private val Danger = Color(0xFFFF7B91)
private val Warning = Color(0xFFFFC66D)
private val TextPrimary = Color(0xFFF5F7FB)
private val TextMuted = Color(0xFFB6C0D3)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DemoScreen()
            }
        }
    }
}

private enum class DemoFilter(val title: String) {
    UNREAD("Unread"),
    ACTIVE("Active"),
    ARCHIVED("Archived"),
    ALL("All"),
}

private enum class DemoStatus(val label: String, val tone: Color) {
    IDLE("Disconnected", TextMuted),
    CONNECTING("Connecting", Warning),
    CONNECTED("Connected", Accent),
    EXPIRED("Session expired", Danger),
    ERROR("Connection failed", Danger),
}

private val environmentBaseUrls = mapOf(
    Environment.PRE_DEV to "https://v3.mentor-pre-dev.neccton.ai/pic",
    Environment.DEV to "https://v3.mentor-dev.neccton.ai/pic",
    Environment.STAGE to "https://v3.mentor-stage.neccton.ai/pic",
    Environment.PROD to "https://v3.mentor.neccton.ai/pic",
)

@Composable
private fun DemoScreen() {
    val scope = rememberCoroutineScope()
    var apiKey by rememberSaveable { mutableStateOf("") }
    var customerId by rememberSaveable { mutableStateOf("") }
    var selectedEnvironment by rememberSaveable { mutableStateOf(Environment.PROD.name) }
    var selectedFilter by rememberSaveable { mutableStateOf(DemoFilter.UNREAD.name) }
    var status by rememberSaveable { mutableStateOf(DemoStatus.IDLE.name) }
    var statusDetail by rememberSaveable { mutableStateOf("Open settings to connect a Lighthouse session.") }
    var unreadCount by rememberSaveable { mutableStateOf(0) }
    var isInitializing by remember { mutableStateOf(false) }
    var isLoadingMessages by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isMarkingAllRead by remember { mutableStateOf(false) }
    var isSettingsPresented by rememberSaveable { mutableStateOf(true) }
    var environmentMenuExpanded by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<CustomerMessage>() }
    var session by remember { mutableStateOf<LighthouseSession?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(AppBackground)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(
                onOpenSettings = { isSettingsPresented = true },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusCard(
                    status = DemoStatus.valueOf(status),
                    detail = statusDetail,
                )

                FilterBar(
                    selectedFilter = DemoFilter.valueOf(selectedFilter),
                    enabled = session != null,
                    onFilterSelected = { filter ->
                        selectedFilter = filter.name
                        scope.launch {
                            isLoadingMessages = true
                            refreshMessages(
                                session = session,
                                filter = filter,
                                messages = messages,
                                setStatus = { newStatus, detail ->
                                    status = newStatus.name
                                    statusDetail = detail
                                },
                                setUnreadCount = { unreadCount = it },
                            )
                            isLoadingMessages = false
                        }
                    },
                )

                ActionRow(
                    isRefreshing = isRefreshing,
                    isMarkingAllRead = isMarkingAllRead,
                    isConnected = session != null,
                    onRefresh = {
                        scope.launch {
                            isRefreshing = true
                            isLoadingMessages = true
                            refreshMessages(
                                session = session,
                                filter = DemoFilter.valueOf(selectedFilter),
                                messages = messages,
                                setStatus = { newStatus, detail ->
                                    status = newStatus.name
                                    statusDetail = detail
                                },
                                setUnreadCount = { unreadCount = it },
                            )
                            isLoadingMessages = false
                            isRefreshing = false
                        }
                    },
                    onMarkAllRead = {
                        scope.launch {
                            val current = session
                            if (current == null) {
                                status = DemoStatus.IDLE.name
                                statusDetail = "Open settings and initialize the SDK first."
                                return@launch
                            }
                            isMarkingAllRead = true
                            runCatching { current.markAllMessagesRead() }
                                .onSuccess {
                                    applyMarkAllReadLocally(messages, DemoFilter.valueOf(selectedFilter))
                                    refreshUnreadCount(current) { unreadCount = it }
                                }
                                .onFailure { throwable ->
                                    status = DemoStatus.ERROR.name
                                    statusDetail = throwable.message ?: throwable::class.simpleName.orEmpty()
                                }
                            isMarkingAllRead = false
                        }
                    },
                )

                MessageFeed(
                    messages = messages,
                    selectedFilter = DemoFilter.valueOf(selectedFilter),
                    status = DemoStatus.valueOf(status),
                    statusDetail = statusDetail,
                    isLoading = isLoadingMessages,
                onRead = { messageId ->
                    scope.launch {
                        val current = session ?: return@launch
                        runCatching { current.markMessageRead(messageId) }
                            .onSuccess {
                                val counts = applyReadLocally(messages, DemoFilter.valueOf(selectedFilter), messageId, unreadCount)
                                unreadCount = counts.unreadCount
                                statusDetail = counts.statusDetail
                                refreshUnreadCount(current) { unreadCount = it }
                            }
                            .onFailure { throwable ->
                                status = DemoStatus.ERROR.name
                                statusDetail = throwable.message ?: throwable::class.simpleName.orEmpty()
                                }
                        }
                    },
                onArchive = { messageId ->
                    scope.launch {
                        val current = session ?: return@launch
                        runCatching { current.archiveMessage(messageId) }
                            .onSuccess {
                                val counts = applyArchiveLocally(messages, DemoFilter.valueOf(selectedFilter), messageId, unreadCount)
                                unreadCount = counts.unreadCount
                                statusDetail = counts.statusDetail
                                refreshUnreadCount(current) { unreadCount = it }
                            }
                            .onFailure { throwable ->
                                status = DemoStatus.ERROR.name
                                statusDetail = throwable.message ?: throwable::class.simpleName.orEmpty()
                                }
                        }
                    },
                onRestore = { messageId ->
                    scope.launch {
                        val current = session ?: return@launch
                        runCatching { current.restoreMessage(messageId) }
                            .onSuccess {
                                val counts = applyRestoreLocally(messages, DemoFilter.valueOf(selectedFilter), messageId, unreadCount)
                                unreadCount = counts.unreadCount
                                statusDetail = counts.statusDetail
                                refreshUnreadCount(current) { unreadCount = it }
                            }
                            .onFailure { throwable ->
                                status = DemoStatus.ERROR.name
                                statusDetail = throwable.message ?: throwable::class.simpleName.orEmpty()
                                }
                        }
                    },
                    onEngage = { messageId, identifier ->
                        scope.launch {
                            val current = session ?: return@launch
                            runCatching { current.engageCallToAction(messageId, identifier) }
                                .onFailure { throwable ->
                                    status = DemoStatus.ERROR.name
                                    statusDetail = throwable.message ?: throwable::class.simpleName.orEmpty()
                                }
                        }
                    },
                )
            }
        }

        if (isSettingsPresented) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.38f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                SettingsSheet(
                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it },
                    customerId = customerId,
                    onCustomerIdChange = { customerId = it },
                    selectedEnvironment = selectedEnvironment,
                    onOpenEnvironmentMenu = { environmentMenuExpanded = true },
                    environmentMenuExpanded = environmentMenuExpanded,
                    onDismissEnvironmentMenu = { environmentMenuExpanded = false },
                    onEnvironmentSelected = {
                        selectedEnvironment = it
                        environmentMenuExpanded = false
                    },
                    isInitializing = isInitializing,
                    onDismiss = { isSettingsPresented = false },
                    onConnect = {
                        isSettingsPresented = false
                        scope.launch {
                            isInitializing = true
                            isLoadingMessages = true
                            status = DemoStatus.CONNECTING.name
                            statusDetail = "Fetching refresh token..."
                            val environment = Environment.valueOf(selectedEnvironment)
                            runCatching {
                                session?.destroy()
                                val refreshToken = fetchRefreshToken(
                                    apiKey = apiKey,
                                    customerId = customerId,
                                    environment = environment,
                                )
                                LighthouseSession.initialize(
                                    refreshToken = refreshToken,
                                    options = InitializeOptions(env = environment),
                                    onRefreshTokenExpired = {
                                        scope.launch {
                                            status = DemoStatus.EXPIRED.name
                                            statusDetail = "Refresh token expired. Reconnect from settings."
                                            session = null
                                        }
                                    },
                                )
                            }.onSuccess { initializedSession ->
                                session = initializedSession
                                status = DemoStatus.CONNECTED.name
                                statusDetail = "Session established. Pulling the latest messages now."
                                refreshMessages(
                                    session = initializedSession,
                                    filter = DemoFilter.valueOf(selectedFilter),
                                    messages = messages,
                                    setStatus = { newStatus, detail ->
                                        status = newStatus.name
                                        statusDetail = detail
                                    },
                                    setUnreadCount = { unreadCount = it },
                                )
                            }.onFailure { throwable ->
                                isSettingsPresented = true
                                session = null
                                unreadCount = 0
                                messages.clear()
                                status = DemoStatus.ERROR.name
                                statusDetail = throwable.message ?: throwable::class.simpleName.orEmpty()
                            }
                            isLoadingMessages = false
                            isInitializing = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Lighthouse", color = AccentMuted, style = MaterialTheme.typography.labelLarge)
            Text(
                "Messages",
                color = TextPrimary,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        TextButton(onClick = onOpenSettings) {
            Text("Settings", color = AccentMuted)
        }
    }
}

@Composable
private fun StatusCard(status: DemoStatus, detail: String) {
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(status.label, color = TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, color = TextMuted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FilterBar(selectedFilter: DemoFilter, enabled: Boolean, onFilterSelected: (DemoFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DemoFilter.entries.forEach { filter ->
            val selected = filter == selectedFilter
            Button(
                onClick = { onFilterSelected(filter) },
                enabled = enabled,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) AccentMuted else Color.White.copy(alpha = 0.05f),
                    contentColor = if (selected) Color.White else TextMuted,
                    disabledContainerColor = Color.White.copy(alpha = 0.03f),
                    disabledContentColor = TextMuted.copy(alpha = 0.5f),
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text(filter.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ActionRow(
    isRefreshing: Boolean,
    isMarkingAllRead: Boolean,
    isConnected: Boolean,
    onRefresh: () -> Unit,
    onMarkAllRead: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onRefresh,
            enabled = isConnected && !isRefreshing,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.06f),
                contentColor = AccentMuted,
                disabledContainerColor = Color.White.copy(alpha = 0.06f),
                disabledContentColor = AccentMuted.copy(alpha = 0.72f),
            ),
        ) {
            Text(if (isRefreshing) "Refreshing..." else "Refresh")
        }

        Button(
            onClick = onMarkAllRead,
            enabled = isConnected && !isMarkingAllRead,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.06f),
                contentColor = TextPrimary,
                disabledContainerColor = Color.White.copy(alpha = 0.06f),
                disabledContentColor = TextPrimary.copy(alpha = 0.72f),
            ),
        ) {
            Text(if (isMarkingAllRead) "Marking..." else "Mark all as read")
        }
    }
}

@Composable
private fun MessageFeed(
    messages: List<CustomerMessage>,
    selectedFilter: DemoFilter,
    status: DemoStatus,
    statusDetail: String,
    isLoading: Boolean,
    onRead: (String) -> Unit,
    onArchive: (String) -> Unit,
    onRestore: (String) -> Unit,
    onEngage: (String, String) -> Unit,
) {
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (isLoading) {
                LoadingState()
            } else if (messages.isEmpty()) {
                EmptyMessageState(status = status, filter = selectedFilter, detail = statusDetail)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    messages.forEach { message ->
                        MessageCard(
                            message = message,
                            onRead = onRead,
                            onArchive = onArchive,
                            onRestore = onRestore,
                            onEngage = onEngage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = AccentMuted)
        Text("Loading messages...", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun EmptyMessageState(status: DemoStatus, filter: DemoFilter, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(status.tone.copy(alpha = 0.18f)),
        )
        Text("No ${filter.title.lowercase()} messages", color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(detail, color = TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MessageCard(
    message: CustomerMessage,
    onRead: (String) -> Unit,
    onArchive: (String) -> Unit,
    onRestore: (String) -> Unit,
    onEngage: (String, String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = GlassStrong),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(message.message.title, color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(message.message.category, color = AccentMuted, style = MaterialTheme.typography.labelMedium)
                }
                MessageStateBadge(message)
            }

            Text(message.message.body, color = TextMuted, style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniBadge(label = message.message.language.uppercase(), tone = AccentMuted)
                MiniBadge(label = message.message.country.uppercase(), tone = Accent)
                if (message.readDate == null) {
                    MiniBadge(label = "New", tone = Warning)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Created ${message.createDate}",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (message.readDate == null) {
                        OutlinedButton(
                            onClick = { onRead(message.customerMessageId) },
                            shape = RoundedCornerShape(999.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                        ) {
                            Text("Mark read", color = TextPrimary)
                        }
                    }
                    if (message.archivedDate == null) {
                        OutlinedButton(
                            onClick = { onArchive(message.customerMessageId) },
                            shape = RoundedCornerShape(999.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                        ) {
                            Text("Archive", color = TextPrimary)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onRestore(message.customerMessageId) },
                            shape = RoundedCornerShape(999.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                        ) {
                            Text("Restore", color = TextPrimary)
                        }
                    }
                }
            }

            if (message.callToActions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    message.callToActions.take(2).forEach { action ->
                        OutlinedButton(
                            onClick = { onEngage(message.customerMessageId, action.identifier) },
                            shape = RoundedCornerShape(999.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                        ) {
                            Text(action.translation, color = AccentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSheet(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    customerId: String,
    onCustomerIdChange: (String) -> Unit,
    selectedEnvironment: String,
    onOpenEnvironmentMenu: () -> Unit,
    environmentMenuExpanded: Boolean,
    onDismissEnvironmentMenu: () -> Unit,
    onEnvironmentSelected: (String) -> Unit,
    isInitializing: Boolean,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        color = Color(0xFF121A29),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Connect session", color = TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onDismiss) {
                    Text("Close", color = AccentMuted)
                }
            }

            DemoTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = "API key",
            )

            DemoTextField(
                value = customerId,
                onValueChange = onCustomerIdChange,
                label = "Customer ID",
            )

            Box {
                DemoTextField(
                    value = selectedEnvironment.lowercase().replace('_', '-'),
                    onValueChange = {},
                    label = "Environment",
                    readOnly = true,
                    trailing = {
                        TextButton(onClick = onOpenEnvironmentMenu) {
                            Text("Change", color = AccentMuted)
                        }
                    },
                )

                DropdownMenu(
                    expanded = environmentMenuExpanded,
                    onDismissRequest = onDismissEnvironmentMenu,
                ) {
                    Environment.entries.forEach { environment ->
                        DropdownMenuItem(
                            text = { Text(environment.name.lowercase().replace('_', '-')) },
                            onClick = { onEnvironmentSelected(environment.name) },
                        )
                    }
                }
            }

            Button(
                onClick = onConnect,
                enabled = apiKey.isNotBlank() && customerId.isNotBlank() && !isInitializing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color(0xFF09111C),
                ),
            ) {
                Text(if (isInitializing) "Connecting..." else "Initialize Lighthouse SDK")
            }
        }
    }
}

@Composable
private fun MessageStateBadge(message: CustomerMessage) {
    val (label, tone) = when {
        message.archivedDate != null -> "Archived" to Danger
        message.readDate != null -> "Read" to Accent
        else -> "Unread" to Warning
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tone.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = tone, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MiniBadge(label: String, tone: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = tone, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Glass,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, GlassBorder, RoundedCornerShape(28.dp))
                .padding(18.dp),
            content = content,
        )
    }
}

@Composable
private fun DemoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        readOnly = readOnly,
        label = { Text(label) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
        shape = RoundedCornerShape(20.dp),
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedLabelColor = AccentMuted,
            unfocusedLabelColor = Color(0xFFAAB6D8),
            focusedBorderColor = GlassBorder,
            unfocusedBorderColor = GlassBorder,
            cursorColor = AccentMuted,
        ),
    )
}

private suspend fun refreshMessages(
    session: LighthouseSession?,
    filter: DemoFilter,
    messages: MutableList<CustomerMessage>,
    setStatus: (DemoStatus, String) -> Unit,
    setUnreadCount: (Int) -> Unit,
) {
    val current = session
    if (current == null) {
        messages.clear()
        setUnreadCount(0)
        setStatus(DemoStatus.IDLE, "Open settings and initialize the SDK first.")
        return
    }

    runCatching {
        val options = MessageListOptions(page = 0, limit = 10)
        val response = when (filter) {
            DemoFilter.UNREAD -> current.unreadMessages(options)
            DemoFilter.ACTIVE -> current.activeMessages(options)
            DemoFilter.ARCHIVED -> current.archivedMessages(options)
            DemoFilter.ALL -> current.messages(options)
        }
        val unreadResponse = current.unreadMessages(MessageListOptions(page = 0, limit = 1))
        response to unreadResponse.totalItems
    }.onSuccess { (response, unreadTotal) ->
        messages.clear()
        messages.addAll(response.data)
        setUnreadCount(unreadTotal)
        setStatus(
            DemoStatus.CONNECTED,
            "Loaded ${response.data.size} ${filter.title.lowercase()} message${if (response.data.size == 1) "" else "s"}.",
        )
    }.onFailure { throwable ->
        setStatus(DemoStatus.ERROR, throwable.message ?: throwable::class.simpleName.orEmpty())
    }
}

private suspend fun refreshUnreadCount(session: LighthouseSession, setUnreadCount: (Int) -> Unit) {
    runCatching {
        session.unreadMessages(MessageListOptions(page = 0, limit = 1)).totalItems
    }.onSuccess(setUnreadCount)
}

private data class LocalUpdateResult(
    val unreadCount: Int,
    val statusDetail: String,
)

private fun applyReadLocally(
    messages: MutableList<CustomerMessage>,
    filter: DemoFilter,
    messageId: String,
    unreadCount: Int,
): LocalUpdateResult {
    if (filter == DemoFilter.UNREAD) {
        messages.removeAll { it.customerMessageId == messageId }
    }
    return LocalUpdateResult(
        unreadCount = (unreadCount - 1).coerceAtLeast(0),
        statusDetail = "Loaded ${messages.size} ${filter.title.lowercase()} message${if (messages.size == 1) "" else "s"}.",
    )
}

private fun applyArchiveLocally(
    messages: MutableList<CustomerMessage>,
    filter: DemoFilter,
    messageId: String,
    unreadCount: Int,
): LocalUpdateResult {
    val removedMessage = messages.firstOrNull { it.customerMessageId == messageId }
    if (filter == DemoFilter.ACTIVE || filter == DemoFilter.UNREAD) {
        messages.removeAll { it.customerMessageId == messageId }
    }
    val unreadDelta = if (removedMessage?.readDate == null) 1 else 0
    return LocalUpdateResult(
        unreadCount = (unreadCount - unreadDelta).coerceAtLeast(0),
        statusDetail = "Loaded ${messages.size} ${filter.title.lowercase()} message${if (messages.size == 1) "" else "s"}.",
    )
}

private fun applyRestoreLocally(
    messages: MutableList<CustomerMessage>,
    filter: DemoFilter,
    messageId: String,
    unreadCount: Int,
): LocalUpdateResult {
    if (filter == DemoFilter.ARCHIVED) {
        messages.removeAll { it.customerMessageId == messageId }
    }
    return LocalUpdateResult(
        unreadCount = unreadCount,
        statusDetail = "Loaded ${messages.size} ${filter.title.lowercase()} message${if (messages.size == 1) "" else "s"}.",
    )
}

private fun applyMarkAllReadLocally(messages: MutableList<CustomerMessage>, filter: DemoFilter) {
    if (filter == DemoFilter.UNREAD) {
        messages.clear()
    }
}

private suspend fun fetchRefreshToken(apiKey: String, customerId: String, environment: Environment): String =
    withContext(Dispatchers.IO) {
        val baseUrl = environmentBaseUrls.getValue(environment)
        val url = URL("$baseUrl/api/v1/authentication/refreshToken")
        val payload = JSONObject()
            .put("apiKey", apiKey)
            .put("customerId", customerId)
            .toString()

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            outputStream.bufferedWriter().use { writer -> writer.write(payload) }
        }

        val responseCode = connection.responseCode
        val body = try {
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }.orEmpty()
        } finally {
            connection.disconnect()
        }

        if (responseCode !in 200..299) {
            throw IllegalStateException(
                body.takeIf { it.isNotBlank() } ?: "Refresh token request failed with HTTP $responseCode",
            )
        }

        JSONObject(body).getString("refreshToken")
    }
