package com.lifeadmin.autopilot.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lifeadmin.autopilot.ui.components.BackHeader
import com.lifeadmin.autopilot.ui.components.PageTitle
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The SIL Open Font License texts of the three bundled font families, read from app assets. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val c = AutopilotTheme.colors
    val context = LocalContext.current
    val licenses by produceState<List<Pair<String, String>>>(emptyList(), context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val assets = context.assets
                assets.list("licenses").orEmpty().sorted().map { name ->
                    name.removePrefix("OFL-").removeSuffix(".txt") to assets.open("licenses/$name").bufferedReader().use { it.readText() }
                }
            }.getOrDefault(emptyList())
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp)) {
        item { BackHeader(null, onBack, Modifier.statusBarsPadding().offset(x = (-8).dp)) }
        item { PageTitle("Open-source licences", Modifier.padding(top = 6.dp, bottom = 8.dp)) }
        item {
            Text(
                "Bricolage Grotesque, Atkinson Hyperlegible Next and Atkinson Hyperlegible Mono are bundled under the SIL Open Font License 1.1 so the app works offline.",
                style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        items(licenses.size) { i ->
            val (name, text) = licenses[i]
            SurfaceCard(modifier = Modifier.padding(bottom = 12.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(text, style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
