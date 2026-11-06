package hu.novaprocess

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import hu.novaprocess.ui.EditScreen
import hu.novaprocess.ui.LibraryScreen
import hu.novaprocess.ui.SettingsScreen
import hu.novaprocess.ui.StackScreen
import hu.novaprocess.ui.UpdatePrompt
import hu.novaprocess.ui.theme.NovaTheme
import hu.novaprocess.vm.EditorViewModel

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel get() = (application as NovaApp).vm

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val night by vm.nightMode.collectAsState()
            NovaTheme(night = night) { NovaRoot(vm) }
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Megosztás → NovaProcess (ACTION_SEND / SEND_MULTIPLE / VIEW). */
    private fun handleShare(intent: android.content.Intent?) {
        intent ?: return
        val uris = ArrayList<android.net.Uri>()
        when (intent.action) {
            android.content.Intent.ACTION_SEND -> (intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM) as? android.net.Uri)?.let { uris.add(it) }
            android.content.Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            android.content.Intent.ACTION_VIEW -> intent.data?.let { uris.add(it) }
            else -> return
        }
        if (uris.isEmpty()) return
        uris.forEach { runCatching { grantUriPermission(packageName, it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        vm.handleIncoming(uris)
        intent.action = null
    }
}

/** Alul lebegő, kapszula alakú navigáció (Darkroom). A képernyők maguk hagynak helyet neki. */
@Composable
fun NovaRoot(vm: EditorViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "stack"
    val hideBar = route == "edit" || route == "batch"

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavHost(navController = nav, startDestination = "stack", modifier = Modifier.fillMaxSize()) {
            composable("stack") { StackScreen(vm, onOpenEditor = { nav.navigate("edit") { launchSingleTop = true } }) }
            composable("edit") { EditScreen(vm, onBack = { nav.popBackStack() }) }
            composable("library") { LibraryScreen(vm, onOpenEditor = { nav.navigate("edit") { launchSingleTop = true } }, onOpenBatch = { nav.navigate("batch") { launchSingleTop = true } }) }
            composable("settings") { SettingsScreen(vm) }
            composable("batch") { hu.novaprocess.ui.BatchBrandScreen(vm, onBack = { nav.popBackStack() }) }
        }
        // értesítési engedély (Android 13+), hogy a kész-értesítés megjelenhessen
        val notifPerm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // bejövő megosztás: 1 kép → szerkesztő, több → kockák
        val incoming by vm.incoming.collectAsState()
        androidx.compose.runtime.LaunchedEffect(incoming) {
            incoming?.let { list -> nav.navigate(if (list.size == 1) "edit" else "stack") { launchSingleTop = true }; vm.consumeIncoming() }
        }
        if (!hideBar) FloatingNav(nav, route, Modifier.align(Alignment.BottomCenter))
        UpdatePrompt(auto = true)
    }
}

@Composable
private fun FloatingNav(nav: NavHostController, route: String, modifier: Modifier) {
    val items = listOf(
        Triple("stack", Icons.Filled.Layers, "Kockák"),
        Triple("edit", Icons.Filled.Tune, "Szerkesztő"),
        Triple("library", Icons.Filled.PhotoLibrary, "Könyvtár"),
        Triple("settings", Icons.Filled.Settings, "Beállítások")
    )
    Row(
        modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth().height(64.dp)
            .clip(CircleShape).background(MaterialTheme.colorScheme.surface),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (r, icon, label) -> NavItem(icon, label, route == r) { nav.navigate(r) { launchSingleTop = true; popUpTo("stack") } } }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (selected) cs.primary else cs.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = if (selected) cs.onPrimary else cs.onSurfaceVariant)
        }
    }
}
