package hu.novaprocess

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import hu.novaprocess.ui.EditScreen
import hu.novaprocess.ui.HomeScreen
import hu.novaprocess.ui.SettingsScreen
import hu.novaprocess.ui.UpdatePrompt
import hu.novaprocess.ui.theme.NovaTheme
import hu.novaprocess.vm.EditorViewModel

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NovaTheme { NovaRoot(vm) } }
    }
}

@Composable
fun NovaRoot(vm: EditorViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = route == "home", onClick = { nav.navigate("home") { launchSingleTop = true; popUpTo("home") } },
                    icon = { Icon(Icons.Filled.Layers, contentDescription = null) }, label = { Text("Stack") })
                NavigationBarItem(selected = route == "edit", onClick = { nav.navigate("edit") { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) }, label = { Text("Szerkesztés") })
                NavigationBarItem(selected = route == "settings", onClick = { nav.navigate("settings") { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) }, label = { Text("Beállítások") })
            }
        }
    ) { pad ->
        NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { HomeScreen(vm, onOpenEditor = { nav.navigate("edit") { launchSingleTop = true } }) }
            composable("edit") { EditScreen(vm) }
            composable("settings") { SettingsScreen() }
        }
        // automatikus frissítés-ellenőrzés indításkor (max. 6 óránként)
        UpdatePrompt(auto = true)
    }
}
