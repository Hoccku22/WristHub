package dev.wristreader.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import dev.wristreader.shared.Document
import dev.wristreader.shared.FileTypes
import dev.wristreader.shared.Transfer
import kotlinx.coroutines.launch

val WatchAccent = Color(0xFF68E0CE)
val WatchInk = Color(0xFF082F2B)
val WatchBackground = Color(0xFF0A1119)
val WatchPanel = Color(0xFF182733)
val WatchMuted = Color(0xFF9DB2C3)
val WatchSoft = Color(0xFF173E3D)

@Composable fun WatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = MaterialTheme.colors.copy(primary = WatchAccent,primaryVariant = WatchAccent,
        secondary = WatchAccent,secondaryVariant = WatchAccent,background = WatchBackground,surface = WatchPanel,
        onPrimary = WatchInk,onSecondary = WatchInk,onBackground = Color(0xFFF0F5FA),
        onSurface = Color(0xFFF0F5FA),onSurfaceVariant = WatchMuted),content = content)
}

@Composable fun WatchHome(receiving: Boolean, transfer: Transfer, error: String, playing: Boolean,
    hasMusic: Boolean, onReceive: () -> Unit, onSection: (HubFilter) -> Unit,
    onSettings: () -> Unit, onMusic: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(WatchBackground).testTag("hub-home"),contentAlignment = Alignment.Center) {
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val needsScroll = maxHeight < 215.dp || LocalDensity.current.fontScale > 1.15f || error.isNotBlank()
        val width = maxWidth * 0.72f
        val container = if (needsScroll) Modifier.fillMaxSize().verticalScroll(scroll)
            .rotary { delta -> scope.launch { scroll.scrollBy(delta) } }.padding(vertical = 30.dp)
            else Modifier.fillMaxSize()
        Column(container,horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.Center) {
            Column(Modifier.width(width),horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("WristHub",style = MaterialTheme.typography.title3)
                Box(Modifier.heightIn(min = 32.dp).clickable(role = Role.Button,onClick = onReceive).testTag("receive-toggle"),contentAlignment = Alignment.Center) {
                    Text(if (receiving && transfer.total > 0) "• Приём · ${transfer.done * 100 / transfer.total}%" else if (receiving) "• Приём включён" else "• Приём выключен",
                        modifier = Modifier.background(WatchSoft,RoundedCornerShape(20.dp)).padding(horizontal = 10.dp,vertical = 3.dp),
                        style = MaterialTheme.typography.caption3,color = WatchAccent,textAlign = TextAlign.Center)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HomeTile("Файлы","folder",Modifier.weight(1f).testTag("home-files")) { onSection(HubFilter.ALL) }
                    HomeTile("Музыка","music",Modifier.weight(1f).testTag("home-music")) { onSection(HubFilter.MUSIC) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HomeTile("APK","package",Modifier.weight(1f).testTag("home-apps")) { onSection(HubFilter.APPS) }
                    HomeTile("Чтение","book",Modifier.weight(1f).testTag("home-reading")) { onSection(HubFilter.READING) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp),verticalAlignment = Alignment.CenterVertically) {
                    if (hasMusic) WatchSmallAction(if (playing) "Играет" else "Плеер","music",onMusic)
                    WatchSmallAction("Настройки","settings",onSettings)
                }
                if (error.isNotBlank()) Text(error,style = MaterialTheme.typography.caption3,textAlign = TextAlign.Center,color = WatchMuted)
            }
        }
    }
}

@Composable private fun HomeTile(label: String, icon: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(15.dp)).background(WatchPanel)
        .clickable(role = Role.Button,onClick = onClick).padding(vertical = 7.dp,horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(3.dp)) {
        WatchIcon(icon,Modifier.size(18.dp),WatchAccent)
        Text(label,style = MaterialTheme.typography.caption2,maxLines = 1)
    }
}

@Composable fun WatchLibrary(documents: List<Document>, filter: HubFilter, free: Long,
    sensitivity: Float, onClose: () -> Unit, onOpen: (Document) -> Unit, onDelete: (Document) -> Unit) {
    BackHandler(onBack = onClose)
    val visible = remember(documents,filter) { documents.filter { filter.accepts(it) } }
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val scope = rememberCoroutineScope()
    val scaling = remember { ScalingLazyColumnDefaults.scalingParams(edgeScale = 0.94f,edgeAlpha = 1f) }
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = state) }) {
        ScalingLazyColumn(state = state,modifier = Modifier.fillMaxSize().testTag("hub-library")
            .rotary { delta -> scope.launch { state.scrollBy(delta * sensitivity) } },
            scalingParams = scaling,autoCentering = null,
            contentPadding = PaddingValues(horizontal = 24.dp,vertical = 32.dp),verticalArrangement = Arrangement.spacedBy(7.dp)) {
            item { Text(when(filter) { HubFilter.ALL -> "Файлы"; HubFilter.APPS -> "Приложения"; else -> filter.label },style = MaterialTheme.typography.title3) }
            item { Text("${visible.size} ${fileWord(visible.size)}",style = MaterialTheme.typography.caption3,color = WatchMuted) }
            if (visible.isEmpty()) item { Text("Пока здесь пусто. Отправь файлы с телефона.",style = MaterialTheme.typography.caption2,textAlign = TextAlign.Center) }
            items(visible.size,key = { visible[it].header.id }) { index ->
                val doc = visible[index]
                Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(WatchPanel)
                        .clickable(role = Role.Button) { onOpen(doc) }.padding(horizontal = 9.dp,vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        WatchIcon(fileIcon(doc.header.mime),Modifier.size(18.dp),WatchAccent)
                        Column(Modifier.weight(1f)) {
                            Text(doc.header.name,style = MaterialTheme.typography.caption2,maxLines = 2,overflow = TextOverflow.Ellipsis)
                            Text("${shortType(doc.header.mime)} · ${"%.1f".format(doc.header.size / 1048576.0)} МБ",
                                style = MaterialTheme.typography.caption3,color = WatchMuted,maxLines = 1,overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(onClick = { onDelete(doc) },modifier = Modifier.size(44.dp).testTag("delete-${doc.header.id}"),
                        colors = ButtonDefaults.buttonColors(backgroundColor = WatchBackground,contentColor = WatchMuted)) {
                        WatchIcon("trash",Modifier.size(18.dp),description = "Удалить ${doc.header.name}")
                    }
                }
            }
            item { Text("Свободно ${"%.0f".format(free / 1048576.0)} МБ",style = MaterialTheme.typography.caption3,color = WatchMuted) }
            item { WatchSmallAction("Главная","back",onClose) }
        }
    }
}

@Composable fun WatchSmallAction(label: String, icon: String, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 32.dp).clickable(role = Role.Button,onClick = onClick).padding(horizontal = 4.dp,vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        WatchIcon(icon,Modifier.size(13.dp),WatchMuted)
        Text(label,style = MaterialTheme.typography.caption3,color = WatchMuted)
    }
}

private fun fileWord(n: Int) = when { n % 100 in 11..14 -> "файлов"; n % 10 == 1 -> "файл"; n % 10 in 2..4 -> "файла"; else -> "файлов" }
private fun fileIcon(mime: String) = when { mime == FileTypes.APK -> "package"; mime.startsWith("audio/") -> "music"; mime.startsWith("image/") -> "image"; else -> "file" }
private fun shortType(mime: String) = when { mime == FileTypes.APK -> "APK"; mime == "application/pdf" -> "PDF"; mime.startsWith("audio/") -> "Аудио"; mime.startsWith("image/") -> "Фото"; else -> "Файл" }

@Composable fun WatchIcon(kind: String, modifier: Modifier = Modifier.size(24.dp), color: Color = LocalContentColor.current, description: String? = null) {
    val vector = remember(kind) { ImageVector.Builder(kind,24.dp,24.dp,24f,24f).apply {
        path(fill = null,stroke = SolidColor(Color.Black),strokeLineWidth = 1.7f,strokeLineCap = StrokeCap.Round,strokeLineJoin = StrokeJoin.Round) {
            when(kind) {
                "folder" -> { moveTo(3f,7f);lineTo(3f,19f);lineTo(21f,19f);lineTo(21f,7f);lineTo(11f,7f);lineTo(9f,4f);lineTo(3f,4f);close() }
                "music" -> { moveTo(9f,17f);lineTo(9f,5f);lineTo(19f,3f);lineTo(19f,15f);moveTo(9f,8f);lineTo(19f,6f);moveTo(9f,17f);curveTo(2f,13f,2f,23f,9f,19f);close();moveTo(19f,15f);curveTo(12f,11f,12f,21f,19f,17f);close() }
                "package" -> { moveTo(12f,2f);lineTo(21f,7f);lineTo(21f,17f);lineTo(12f,22f);lineTo(3f,17f);lineTo(3f,7f);close();moveTo(3f,7f);lineTo(12f,12f);lineTo(21f,7f);moveTo(12f,12f);lineTo(12f,22f) }
                "book" -> { moveTo(3f,4f);lineTo(10f,5f);lineTo(12f,7f);lineTo(14f,5f);lineTo(21f,4f);lineTo(21f,19f);lineTo(14f,20f);lineTo(12f,22f);lineTo(10f,20f);lineTo(3f,19f);close();moveTo(12f,7f);lineTo(12f,22f) }
                "settings" -> { moveTo(4f,6f);lineTo(20f,6f);moveTo(4f,12f);lineTo(20f,12f);moveTo(4f,18f);lineTo(20f,18f);moveTo(8f,3f);lineTo(8f,9f);moveTo(16f,9f);lineTo(16f,15f);moveTo(10f,15f);lineTo(10f,21f) }
                "trash" -> { moveTo(4f,6f);lineTo(20f,6f);moveTo(9f,6f);lineTo(9f,3f);lineTo(15f,3f);lineTo(15f,6f);moveTo(6f,6f);lineTo(7f,21f);lineTo(17f,21f);lineTo(18f,6f);moveTo(10f,10f);lineTo(10f,17f);moveTo(14f,10f);lineTo(14f,17f) }
                "back" -> { moveTo(15f,5f);lineTo(8f,12f);lineTo(15f,19f) }
                "play" -> { moveTo(8f,4f);lineTo(20f,12f);lineTo(8f,20f);close() }
                "pause" -> { moveTo(7f,4f);lineTo(7f,20f);moveTo(17f,4f);lineTo(17f,20f) }
                "previous" -> { moveTo(5f,5f);lineTo(5f,19f);moveTo(19f,5f);lineTo(8f,12f);lineTo(19f,19f);close() }
                "next" -> { moveTo(19f,5f);lineTo(19f,19f);moveTo(5f,5f);lineTo(16f,12f);lineTo(5f,19f);close() }
                "image" -> { moveTo(3f,3f);lineTo(21f,3f);lineTo(21f,21f);lineTo(3f,21f);close();moveTo(3f,17f);lineTo(9f,11f);lineTo(13f,15f);lineTo(16f,12f);lineTo(21f,17f) }
                else -> { moveTo(5f,2f);lineTo(14f,2f);lineTo(19f,7f);lineTo(19f,22f);lineTo(5f,22f);close();moveTo(14f,2f);lineTo(14f,7f);lineTo(19f,7f);moveTo(8f,12f);lineTo(16f,12f);moveTo(8f,16f);lineTo(14f,16f) }
            }
        }
    }.build() }
    Icon(vector,description,modifier,tint = color)
}
