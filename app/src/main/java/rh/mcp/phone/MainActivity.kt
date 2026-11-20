package rh.mcp.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhoneToMcpTheme { MainScreen() } }
    }
}

@Composable
private fun PhoneToMcpTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else MaterialTheme.colorScheme
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var serverEnabled by remember { mutableStateOf(ToolSettings.serverEnabled(context)) }
    var serverRunning by remember { mutableStateOf(false) }
    var tunnelEnabled by remember { mutableStateOf(ToolSettings.tunnelEnabled(context)) }
    var tunnelUrl by remember { mutableStateOf<String?>(null) }
    var tunnelError by remember { mutableStateOf<String?>(null) }
    var authEnabled by remember { mutableStateOf(ToolSettings.authEnabled(context)) }
    var token by remember { mutableStateOf(ToolSettings.token(context)) }
    var backend by remember { mutableStateOf(ToolSettings.backend(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    val tools = remember(refresh) { ToolRegistry.all }
    val prefs = remember { context.getSharedPreferences("server", Activity.MODE_PRIVATE) }

    LaunchedEffect(Unit) {
        while (true) {
            serverEnabled = ToolSettings.serverEnabled(context)
            serverRunning = prefs.getBoolean("server_running", false)
            tunnelEnabled = ToolSettings.tunnelEnabled(context)
            tunnelUrl = prefs.getString("public_url", null)
            tunnelError = prefs.getString("tunnel_error", null)
            authEnabled = ToolSettings.authEnabled(context)
            backend = ToolSettings.backend(context)
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher),
                            contentDescription = null,
                            modifier = Modifier.size(38.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Phone To MCP", fontWeight = FontWeight.SemiBold)
                            Text(
                                "android control server",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Rounded.PowerSettingsNew, null) }, label = { Text("server") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Rounded.Tune, null) }, label = { Text("tools") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Rounded.History, null) }, label = { Text("logs") })
            }
        },
    ) { padding ->
        when (tab) {
            0 -> DashboardPage(
                modifier = Modifier.padding(padding),
                serverEnabled = serverEnabled,
                serverRunning = serverRunning,
                tunnelEnabled = tunnelEnabled,
                tunnelUrl = tunnelUrl,
                tunnelError = tunnelError,
                authEnabled = authEnabled,
                token = token,
                backend = backend,
                onServerChanged = { enabled ->
                    if (enabled) PhoneMcpService.start(context) else PhoneMcpService.stop(context)
                    serverEnabled = enabled
                },
                onTunnelChanged = { enabled ->
                    if (enabled) PhoneMcpService.startTunnel(context) else PhoneMcpService.stopTunnel(context)
                    tunnelEnabled = enabled
                },
                onAuthChanged = { enabled -> ToolSettings.setAuthEnabled(context, enabled); authEnabled = enabled },
                onTokenCopy = { copyText(context, "token MCP", token) },
                onTokenRegenerate = { token = ToolSettings.regenerateToken(context) },
                onBackendChanged = { selected ->
                    runCatching { TerminalManager.setBackend(context, selected) }
                        .onSuccess { backend = ToolSettings.backend(context) }
                        .onFailure {
                            RuntimeLog.event(context, "terminal backend setting rejected", it)
                            Toast.makeText(context, it.message ?: "backend gagal diubah", Toast.LENGTH_LONG).show()
                        }
                },
                onStoragePermission = { requestStoragePermission(context) },
                onNotificationPermission = { requestNotificationPermission(context) },
                onNotificationAccess = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                        .onFailure { RuntimeLog.event(context, "cannot open notification access settings", it) }
                },
                onTermuxSettings = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${context.packageName}")))
                    }.onFailure { RuntimeLog.event(context, "cannot open app permission settings", it) }
                },
            )
            1 -> ToolsPage(Modifier.padding(padding), tools, refresh) { tool, enabled ->
                ToolSettings.setEnabled(context, tool, enabled)
                refresh++
            }
            else -> LogsPage(Modifier.padding(padding), refresh)
        }
    }
}

@Composable
private fun DashboardPage(
    modifier: Modifier,
    serverEnabled: Boolean,
    serverRunning: Boolean,
    tunnelEnabled: Boolean,
    tunnelUrl: String?,
    tunnelError: String?,
    authEnabled: Boolean,
    token: String,
    backend: String,
    onServerChanged: (Boolean) -> Unit,
    onTunnelChanged: (Boolean) -> Unit,
    onAuthChanged: (Boolean) -> Unit,
    onTokenCopy: () -> Unit,
    onTokenRegenerate: () -> Unit,
    onBackendChanged: (String) -> Unit,
    onStoragePermission: () -> Unit,
    onNotificationPermission: () -> Unit,
    onNotificationAccess: () -> Unit,
    onTermuxSettings: () -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (serverRunning) Icons.Rounded.CheckCircle else Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "status server",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            )
                            Text(
                                when {
                                    serverRunning -> "server aktif"
                                    serverEnabled -> "server sedang dimulai"
                                    else -> "server berhenti"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    UrlRow(
                        label = "url lokal",
                        value = "http://127.0.0.1:${McpServer.PORT}/mcp",
                        onCopy = { copyText(context, "URL lokal", "http://127.0.0.1:${McpServer.PORT}/mcp") },
                    )
                    Button(
                        onClick = { onServerChanged(!serverEnabled) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (serverEnabled) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (serverEnabled) "stop server" else "start server")
                    }
                }
            }
        }
        item {
            SectionCard("cloudflare tunnel", Icons.Rounded.Cloud) {
                Text(
                    when {
                        !tunnelUrl.isNullOrBlank() -> "tunnel aktif"
                        tunnelEnabled -> "menunggu URL publik dari cloudflared"
                        else -> "akses publik opsional melalui Cloudflare"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!tunnelUrl.isNullOrBlank()) {
                    UrlRow(
                        label = "url publik",
                        value = tunnelUrl,
                        onCopy = { copyText(context, "URL tunnel", tunnelUrl) },
                    )
                } else if (!tunnelError.isNullOrBlank()) {
                    Text(tunnelError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onTunnelChanged(!tunnelEnabled) }) {
                        Icon(Icons.Rounded.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (tunnelEnabled) "stop tunnel" else "start tunnel")
                    }
                }
            }
        }
        item {
            SectionCard("akses MCP", Icons.Rounded.Lock) {
                SettingSwitch("bearer authentication", "token diwajibkan untuk request MCP", authEnabled, onAuthChanged)
                Text(if (authEnabled) token else "auth nonaktif — request tidak memerlukan token",
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTokenCopy, enabled = authEnabled) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("copy")
                    }
                    OutlinedButton(onClick = onTokenRegenerate) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("regenerate")
                    }
                }
            }
        }
        item {
            SectionCard("terminal backend", Icons.Rounded.Terminal) {
                Text("android shell adalah default; tidak ada automatic fallback.", style = MaterialTheme.typography.bodySmall)
                BackendOption("Android shell", "application UID, bukan root", backend == "android") { onBackendChanged("android") }
                BackendOption(
                    "Termux",
                    when {
                        !TerminalManager.termuxInstalled(context) -> "Termux belum terpasang"
                        !TerminalManager.termuxPermissionGranted(context) -> "butuh izin RUN_COMMAND; ketuk untuk buka pengaturan"
                        else -> "command dijalankan melalui Termux"
                    },
                    backend == "termux",
                    enabled = TerminalManager.termuxInstalled(context),
                    onClick = {
                        when {
                            !TerminalManager.termuxInstalled(context) ->
                                Toast.makeText(context, "Termux belum terpasang", Toast.LENGTH_SHORT).show()
                            !TerminalManager.termuxPermissionGranted(context) -> onTermuxSettings()
                            else -> onBackendChanged("termux")
                        }
                    },
                )
                if (TerminalManager.termuxInstalled(context) && !TerminalManager.termuxPermissionGranted(context)) {
                    OutlinedButton(onClick = onTermuxSettings) {
                        Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("buka pengaturan izin aplikasi")
                    }
                }
            }
        }
        item {
            SectionCard("izin perangkat", Icons.Rounded.Security) {
                PermissionRow(
                    "shared storage",
                    if (Build.VERSION.SDK_INT >= 30) {
                        if (EnvironmentPermission.allFiles(context)) "akses penuh aktif" else "butuh all files access"
                    } else "storage permission diperlukan",
                    Icons.Rounded.FolderOpen,
                    onStoragePermission,
                )
                PermissionRow("notification access", "untuk notification.read", Icons.Rounded.Notifications, onNotificationAccess)
                if (Build.VERSION.SDK_INT >= 33) PermissionRow(
                    "post notifications",
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED) "diizinkan" else "belum diizinkan",
                    Icons.Rounded.Notifications,
                    onNotificationPermission,
                )
            }
        }
        item {
            Text("service log: /sdcard/Android/media/rh.mcp.phone/logs", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UrlRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = "salin $label")
        }
    }
}

@Composable
private fun ToolsPage(modifier: Modifier, tools: List<ToolDef>, refresh: Int, onChanged: (ToolDef, Boolean) -> Unit) {
    val categories = tools.groupBy { it.category }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("akses tool", Icons.Rounded.Tune) {
                Text("tool write, terminal, notifikasi, download, dan TTS mati sampai lu aktifkan.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        categories.forEach { (category, entries) ->
            item(key = "header-$category") {
                Text(category.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            items(entries, key = { it.name }) { tool ->
                ToolRow(tool, ToolSettings.isEnabled(LocalContext.current, tool), refresh, onChanged)
            }
        }
    }
}

@Composable
private fun ToolRow(tool: ToolDef, enabled: Boolean, refresh: Int, onChanged: (ToolDef, Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tool.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = { onChanged(tool, it) })
        }
    }
}

@Composable
private fun LogsPage(modifier: Modifier, refresh: Int) {
    val context = LocalContext.current
    var terminalRows by remember { mutableStateOf(emptyList<TerminalLogEntry>()) }
    var runtimeLog by remember { mutableStateOf("") }
    LaunchedEffect(refresh) {
        terminalRows = runCatching { TerminalLogDb(context).use { it.recent(50) } }.getOrDefault(emptyList())
        runtimeLog = runCatching {
            val log = File(RuntimeLog.directory(), "runtime.log")
            if (log.isFile) log.readLines().takeLast(120).joinToString("\n") else "belum ada log runtime"
        }.getOrElse { "log belum dapat dibaca: ${it.message}" }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("runtime log", Icons.Rounded.BugReport) {
                Text(runtimeLog, style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("terminal history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        if (terminalRows.isEmpty()) item { Text("belum ada terminal execution", style = MaterialTheme.typography.bodySmall) }
        items(terminalRows, key = { it.id }) { row ->
            SectionCard("${row.backend} · exit ${row.exitCode}", Icons.Rounded.Terminal) {
                Text("$ ${row.command}", style = MaterialTheme.typography.bodyMedium)
                Text("shell: ${row.shell}\ncwd: ${row.cwd}\n${row.durationMs} ms${if (row.timedOut) " · timeout" else ""}",
                    style = MaterialTheme.typography.labelSmall)
                if (row.stdout.isNotBlank()) Text(row.stdout.take(1200), style = MaterialTheme.typography.bodySmall)
                if (row.stderr.isNotBlank()) Text(row.stderr.take(1200), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                row.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange)
    }
}

@Composable
private fun BackendOption(title: String, subtitle: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { androidx.compose.material3.RadioButton(selected, onClick = null, enabled = enabled) },
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun PermissionRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    androidx.compose.material3.ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { androidx.compose.material3.IconButton(onClick) { Icon(Icons.Rounded.OpenInNew, null) } },
    )
}

private fun requestStoragePermission(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= 30) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}")))
        }.onFailure { RuntimeLog.event(context, "cannot open all files settings", it) }
    } else if (context is Activity) {
        context.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 9026)
    }
}

private fun requestNotificationPermission(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= 33 && context is Activity) {
        context.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9033)
    }
}

private fun copyText(context: android.content.Context, label: String, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label disalin", Toast.LENGTH_SHORT).show()
}

private object EnvironmentPermission {
    fun allFiles(context: android.content.Context): Boolean =
        Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
}
