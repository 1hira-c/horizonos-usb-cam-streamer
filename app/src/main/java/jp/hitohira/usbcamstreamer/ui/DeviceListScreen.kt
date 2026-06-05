package jp.hitohira.usbcamstreamer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.hitohira.usbcamstreamer.usb.DeviceSummary

/** 接続中の USB デバイス一覧。タップで権限要求→接続（複数台を順次接続可）。 */
@Composable
fun DeviceListScreen(
    devices: List<DeviceSummary>,
    connectedNames: Set<String>,
    onRefresh: () -> Unit,
    onConnect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("USB デバイス (${devices.size})", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onRefresh) { Text("再列挙") }
        }

        if (devices.isEmpty()) {
            Text(
                "デバイスがありません。OTG で USB デバイス(UVCカメラ等)を接続してください。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            LazyColumn {
                items(devices, key = { it.deviceName }) { d ->
                    val connected = d.deviceName in connectedNames
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable(enabled = !connected) { onConnect(d.deviceName) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                (d.productName ?: d.deviceName) + if (connected) "  ・接続済み" else "",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("VID:PID = ${d.vidPid}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "class=${d.deviceClass} interfaces=${d.interfaceCount}" +
                                    if (d.isLikelyUvc) "  ・UVC候補" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            d.manufacturerName?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
