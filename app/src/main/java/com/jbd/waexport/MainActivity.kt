package com.jbd.waexport

import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jbd.waexport.model.Chat
import com.jbd.waexport.ui.AppCard
import com.jbd.waexport.ui.Avatar
import com.jbd.waexport.ui.BottomSelectionBar
import com.jbd.waexport.ui.StatusChip
import com.jbd.waexport.ui.theme.MintDeep
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )

        setContent {
            com.jbd.waexport.ui.theme.WaExporterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

enum class Screen { EXPORT, MY_EXPORTS }

@Composable
fun AppNavigation() {
    var currentScreen by remember { mutableStateOf(Screen.EXPORT) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = currentScreen == Screen.EXPORT,
                    onClick = { currentScreen = Screen.EXPORT },
                    icon = { Icon(Icons.Default.Email, contentDescription = "Chats") },
                    label = { Text("Chats") },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = MintDeep.copy(alpha = 0.2f), selectedIconColor = MintDeep, selectedTextColor = MintDeep)
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.MY_EXPORTS,
                    onClick = { currentScreen = Screen.MY_EXPORTS },
                    icon = { Icon(Icons.Default.Folder, contentDescription = "Exports") },
                    label = { Text("Exports") },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = MintDeep.copy(alpha = 0.2f), selectedIconColor = MintDeep, selectedTextColor = MintDeep)
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (currentScreen) {
                Screen.EXPORT -> MainScreen()
                Screen.MY_EXPORTS -> MyExportsScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    var rawDiagnostics by remember { mutableStateOf("Checking root...") }
    var isRootGranted by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var chats by remember { mutableStateOf<List<Chat>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    // Export state
    var showModeChooser by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var exportStatusMessage by remember { mutableStateOf("") }
    var exportProgress by remember { mutableStateOf(0f) }
    var exportIsDone by remember { mutableStateOf(false) }
    var exportStats by remember { mutableStateOf<List<ExportStats>>(emptyList()) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val granted = withContext(Dispatchers.IO) { Shell.getShell().isRoot }
        isRootGranted = granted
        rawDiagnostics = if (granted) {
            val res = withContext(Dispatchers.IO) { Shell.cmd("id").exec() }
            if (res.isSuccess) "Root OK: " + res.out.joinToString(" ")
            else "Root granted, but 'id' failed"
        } else {
            "Root access denied"
        }
    }

    val visibleChats = chats.filter {
        searchQuery.isBlank() ||
            (it.displayName ?: "").contains(searchQuery, ignoreCase = true) ||
            it.jid.contains(searchQuery, ignoreCase = true)
    }.sortedByDescending { it.timestamp }

    // --- Helper to run the export with a chosen mode ---
    fun runExport(mode: ExportMode) {
        showModeChooser = false
        showExportSheet = true
        exportIsDone = false
        exportStatusMessage = "Preparing..."
        exportProgress = 0f
        exportStats = emptyList()

        scope.launch {
            val selectedChats = chats.filter { selected.contains(it.jid) }
            val total = selectedChats.size
            val results = mutableListOf<ExportStats>()

            withContext(Dispatchers.IO) {
                for ((i, chat) in selectedChats.withIndex()) {
                    val baseProgress = i.toFloat() / total
                    val chatFraction = 1f / total

                    val stats = ExportRepo.exportChat(context, chat, mode) { statusMsg, fraction ->
                        exportStatusMessage = "${chat.displayName}:\n$statusMsg"
                        exportProgress = baseProgress + (fraction * chatFraction)
                    }

                    if (stats != null) results.add(stats)
                }
            }

            if (results.isNotEmpty()) {
                exportProgress = 1f
                exportStatusMessage = "Finished exporting $total chat(s)."
                exportIsDone = true
                exportStats = results
                selected = emptySet()
            } else {
                exportStatusMessage = "Export failed."
                exportIsDone = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "WA Exporter", style = MaterialTheme.typography.titleLarge)
                StatusChip(isReady = isRootGranted, onClick = { showDiagnostics = true })
            }

            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                item {
                    AppCard(onClick = {
                        if (!isRootGranted) return@AppCard
                        scope.launch {
                            isLoading = true
                            val copied = withContext(Dispatchers.IO) { RootRepo.copyDatabases(context) }
                            if (copied.isEmpty()) {
                                status = "Couldn't reach WhatsApp — tap to fix"
                            } else {
                                val loaded = withContext(Dispatchers.IO) { ChatRepo.getChats(context) }
                                chats = loaded
                                status = "${loaded.size} chats · updated just now"
                            }
                            isLoading = false
                        }
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = MintDeep)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Refresh chats", fontWeight = FontWeight.Bold)
                                Text("Pull the latest from WhatsApp", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    if (status.isNotBlank()) {
                        Text(text = status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                    }
                }

                if (chats.isNotEmpty() || isLoading) {
                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Search clients") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    if (isLoading) {
                        items(5) {
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Box(modifier = Modifier.height(16.dp).width(120.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                                    Spacer(Modifier.height(4.dp))
                                    Box(modifier = Modifier.height(12.dp).width(200.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                                }
                            }
                        }
                    } else {
                        items(visibleChats) { chat ->
                            val isChecked = selected.contains(chat.jid)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (isChecked) selected - chat.jid else selected + chat.jid
                                    }
                                    .background(if (isChecked) MintDeep.copy(alpha = 0.1f) else Color.Transparent)
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = MintDeep)
                                )
                                Spacer(Modifier.width(8.dp))
                                Avatar(name = chat.displayName ?: "Unknown")
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = chat.displayName ?: chat.jid, fontWeight = FontWeight.Bold)
                                    val timeStr = if (chat.timestamp > 0) {
                                        SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date(chat.timestamp))
                                    } else "Unknown time"
                                    Text(
                                        text = "Active $timeStr",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom selection bar
        if (selected.isNotEmpty()) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                BottomSelectionBar(
                    count = selected.size,
                    onClear = { selected = emptySet() },
                    onExport = { showModeChooser = true }
                )
            }
        }
    }

    // ─── Mode chooser dialog ───────────────────────────────────────
    if (showModeChooser) {
        AlertDialog(
            onDismissRequest = { showModeChooser = false },
            title = { Text("Export Mode") },
            text = { Text("Choose what to include in the shareable bundle:") },
            confirmButton = {
                Button(
                    onClick = { runExport(ExportMode.FULL) },
                    colors = ButtonDefaults.buttonColors(containerColor = MintDeep)
                ) { Text("Full Export") }
            },
            dismissButton = {
                OutlinedButton(onClick = { runExport(ExportMode.RECENT_7D) }) {
                    Text("Update (Last 7 Days)")
                }
            }
        )
    }

    // ─── Export progress / results sheet ────────────────────────────
    @OptIn(ExperimentalMaterial3Api::class)
    if (showExportSheet) {
        ModalBottomSheet(
            onDismissRequest = { if (exportIsDone) showExportSheet = false }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).padding(bottom = 24.dp)) {
                Text(
                    text = if (exportIsDone) "Export Complete" else "Exporting Chats",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))
                Text(text = exportStatusMessage)
                Spacer(Modifier.height(16.dp))

                if (!exportIsDone) {
                    LinearProgressIndicator(progress = { exportProgress }, modifier = Modifier.fillMaxWidth(), color = MintDeep)
                } else if (exportStats.isNotEmpty()) {
                    // Breakdown card
                    AppCard {
                        Text("Export Breakdown", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        exportStats.forEach { s ->
                            Text("• Messages read: ${s.messagesRead}", style = MaterialTheme.typography.bodyMedium)
                            Text("• New media copied: ${s.newMediaCopied}", style = MaterialTheme.typography.bodyMedium)
                            Text("• Existing media skipped: ${s.existingMediaSkipped}", style = MaterialTheme.typography.bodyMedium)
                            Text("• Bundle: ${s.bundleType} · ${Formatter.formatShortFileSize(context, s.bundleSize)}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    // Share button
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MintDeep),
                        onClick = {
                            val uris = exportStats.map { s ->
                                androidx.core.content.FileProvider.getUriForFile(context, "com.jbd.waexport.fileprovider", s.bundleFile)
                            }
                            val intent = android.content.Intent(
                                if (uris.size == 1) android.content.Intent.ACTION_SEND
                                else android.content.Intent.ACTION_SEND_MULTIPLE
                            ).apply {
                                type = "application/zip"
                                if (uris.size == 1) {
                                    putExtra(android.content.Intent.EXTRA_STREAM, uris.first())
                                } else {
                                    putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                                }
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share Export"))
                        }
                    ) {
                        Text("Share Bundle${if (exportStats.size > 1) "s" else ""}")
                    }
                }
            }
        }
    }

    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("Diagnostics") },
            text = { Text(rawDiagnostics) },
            confirmButton = {
                TextButton(onClick = { showDiagnostics = false }) {
                    Text("OK", color = MintDeep)
                }
            }
        )
    }
}

// ─── Data class for My Exports ─────────────────────────────────────

data class ExportItem(
    val dir: File,
    val name: String,
    val lastModified: Long,
    val totalSize: Long,
    val msgCount: Int,
    val isLocked: Boolean
)

data class BundleItem(
    val file: File,
    val type: String,   // "full" or "recent7d"
    val date: Long,
    val size: Long
)

// ─── My Exports screen with expandable per-client rows ─────────────

@Composable
fun MyExportsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var exports by remember { mutableStateOf<List<ExportItem>>(emptyList()) }
    var totalStorage by remember { mutableStateOf(0L) }
    var isLoading by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("") }
    var expandedDirs by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun loadExports() {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            val root = File(context.getExternalFilesDir(null), "exports")
            if (!root.exists()) root.mkdirs()

            var total = 0L
            val list = root.listFiles()?.filter { it.isDirectory }?.map { dir ->
                val size = dir.walkTopDown().map { it.length() }.sum()
                total += size

                var msgCount = 0
                val jsonFile = File(dir, "messages.json")
                if (jsonFile.exists()) {
                    try {
                        val arr = JSONArray(jsonFile.readText())
                        msgCount = arr.length()
                    } catch (e: Exception) { /* ignore */ }
                }

                // Read display name from meta.json
                var displayName = dir.name
                val metaFile = File(dir, "meta.json")
                if (metaFile.exists()) {
                    try {
                        val meta = org.json.JSONObject(metaFile.readText())
                        val dn = meta.optString("displayName", "")
                        if (dn.isNotBlank()) displayName = dn
                    } catch (_: Exception) {}
                }

                val locked = File(dir, ".locked").exists()

                ExportItem(
                    dir = dir, name = displayName,
                    lastModified = dir.lastModified(), totalSize = size,
                    msgCount = msgCount, isLocked = locked
                )
            } ?: emptyList()

            withContext(Dispatchers.Main) {
                exports = list.sortedByDescending { it.lastModified }
                totalStorage = total
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadExports() }

    val df = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("My Exports", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Total Storage: ${Formatter.formatShortFileSize(context, totalStorage)}")

        if (statusMessage.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(statusMessage, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(16.dp))

        if (isLoading) {
            Text("Loading...")
        } else if (exports.isEmpty()) {
            Text("No exports found.")
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(exports) { item ->
                    val isExpanded = expandedDirs.contains(item.dir.absolutePath)

                    // Load bundles for this item
                    val bundles = remember(item.dir, isExpanded) {
                        if (!isExpanded) emptyList()
                        else {
                            val bundlesDir = File(item.dir, "bundles")
                            bundlesDir.listFiles()?.filter { it.extension == "zip" }?.map { f ->
                                val bType = when {
                                    f.name.endsWith("-full.zip") -> "Full"
                                    f.name.endsWith("-update.zip") -> "Update"
                                    else -> "Other"
                                }
                                BundleItem(file = f, type = bType, date = f.lastModified(), size = f.length())
                            }?.sortedByDescending { it.date } ?: emptyList()
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Client header — tap to expand
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    expandedDirs = if (isExpanded) expandedDirs - item.dir.absolutePath
                                    else expandedDirs + item.dir.absolutePath
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(name = item.name, modifier = Modifier.size(40.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(item.name, fontWeight = FontWeight.Bold)
                                        if (item.isLocked) Text(" 🔒")
                                    }
                                    Text(
                                        "Updated: ${df.format(Date(item.lastModified))} · ${Formatter.formatShortFileSize(context, item.totalSize)} · ${item.msgCount} msgs",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Expand"
                                )
                            }

                            // Expanded: bundles + actions
                            AnimatedVisibility(visible = isExpanded) {
                                Column {
                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(8.dp))

                                    // Client-level actions
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                if (item.isLocked) File(item.dir, ".locked").delete()
                                                else File(item.dir, ".locked").createNewFile()
                                                loadExports()
                                            }
                                        }, modifier = Modifier.height(32.dp)) {
                                            Text(if (item.isLocked) "Unlock" else "Lock", style = MaterialTheme.typography.labelSmall)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                if (item.isLocked) {
                                                    statusMessage = "Cannot delete locked export. Unlock first."
                                                } else {
                                                    scope.launch(Dispatchers.IO) {
                                                        item.dir.deleteRecursively()
                                                        withContext(Dispatchers.Main) { statusMessage = "Deleted ${item.name}" }
                                                        loadExports()
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("Delete All", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))
                                    Text("Bundles", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
                                    Spacer(Modifier.height(4.dp))

                                    if (bundles.isEmpty()) {
                                        Text("No bundles yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        bundles.forEach { bundle ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Type badge
                                                Surface(
                                                    color = if (bundle.type == "Full") MintDeep.copy(alpha = 0.15f) else MaterialTheme.colorScheme.secondaryContainer,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        bundle.type,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (bundle.type == "Full") MintDeep else MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                }
                                                Spacer(Modifier.width(8.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date(bundle.date)),
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                    Text(
                                                        Formatter.formatShortFileSize(context, bundle.size),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }

                                                // Share
                                                TextButton(onClick = {
                                                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.jbd.waexport.fileprovider", bundle.file)
                                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                        type = "application/zip"
                                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(android.content.Intent.createChooser(intent, "Share Bundle"))
                                                }) { Text("Share", color = MintDeep) }

                                                // Delete
                                                TextButton(onClick = {
                                                    scope.launch(Dispatchers.IO) {
                                                        bundle.file.delete()
                                                        loadExports()
                                                    }
                                                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
