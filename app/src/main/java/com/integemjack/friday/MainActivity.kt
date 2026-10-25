package com.integemjack.friday

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.integemjack.friday.ui.ActivityScreen
import com.integemjack.friday.ui.ChatScreen
import com.integemjack.friday.ui.HomeScaffold
import com.integemjack.friday.ui.LocalAppModel
import com.integemjack.friday.ui.SettingsScreen
import com.integemjack.friday.ui.SessionsScreen
import com.integemjack.friday.ui.theme.FridayTheme

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = (application as FridayApp).model
        setContent {
            FridayTheme {
                CompositionLocalProvider(LocalAppModel provides model) {
                    AppRoot()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showChat by rememberSaveable { mutableStateOf(false) }
    if (showChat) {
        ChatScreen(onBack = { showChat = false })
    } else {
        HomeScaffold(
            tab = tab,
            onTab = { tab = it },
            sessions = { SessionsScreen(onOpen = { showChat = true }) },
            activity = { ActivityScreen() },
            settings = { SettingsScreen() },
        )
    }
}
