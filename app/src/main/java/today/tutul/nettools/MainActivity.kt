package today.tutul.nettools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import today.tutul.nettools.ui.IpScreen
import today.tutul.nettools.ui.PingScreen
import today.tutul.nettools.ui.TracerouteScreen
import today.tutul.nettools.ui.theme.NetToolsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetToolsTheme {
                NetToolsApp()
            }
        }
    }
}

private val TABS = listOf("IP", "Ping", "Traceroute")

@Composable
fun NetToolsApp() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.statusBarsPadding()) {
                TABS.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when (tab) {
                0 -> IpScreen()
                1 -> PingScreen()
                else -> TracerouteScreen()
            }
        }
    }
}
