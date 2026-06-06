package jp.hitohira.usbcamstreamer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme

/** ダッシュボードのタブ。 */
enum class DashboardTab(val label: String) {
    Cameras("Cameras"),
    Preview("Preview"),
    System("System"),
}

/**
 * Header + 横タブ + Footer のダッシュボードシェル。中身は [content] に [selectedTab] を渡して描画する。
 * 配色は Meta Spatial テーマ（[LocalColorScheme] / [SpatialTheme]）をそのまま使う。
 */
@Composable
fun DashboardScaffold(
    selectedTab: DashboardTab,
    onSelectTab: (DashboardTab) -> Unit,
    note: String,
    serviceRunning: Boolean,
    content: @Composable (DashboardTab) -> Unit,
) {
    Column(
        Modifier.fillMaxSize()
            .clip(SpatialTheme.shapes.large)
            .background(brush = LocalColorScheme.current.panel),
    ) {
        Header()
        TabBar(selectedTab = selectedTab, onSelectTab = onSelectTab)

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            content(selectedTab)
        }

        Footer(note = note, serviceRunning = serviceRunning)
    }
}

@Composable
private fun Header() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Videocam,
            contentDescription = null,
            tint = LocalColorScheme.current.primaryAlphaBackground,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "UVC → PC ストリーミング ダッシュボード",
            style = SpatialTheme.typography.headline3,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TabBar(selectedTab: DashboardTab, onSelectTab: (DashboardTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        DashboardTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val color =
                if (selected) LocalColorScheme.current.primaryAlphaBackground
                else LocalColorScheme.current.secondaryAlphaBackground
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelectTab(tab) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    tab.label,
                    color = color,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 18.sp,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .height(3.dp)
                        .width(60.dp)
                        .background(if (selected) color else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun Footer(note: String, serviceRunning: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            note,
            fontSize = 11.sp,
            color = LocalColorScheme.current.secondaryAlphaBackground,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (serviceRunning) StreamingColor else StoppedColor),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (serviceRunning) "サービス稼働中" else "待機中",
                fontSize = 10.sp,
                color = LocalColorScheme.current.secondaryAlphaBackground,
            )
        }
    }
}
