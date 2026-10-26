package dev.wristreader.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material.*

/** Separate modal window: list position never decides where confirmation appears. */
@Composable fun DeleteFileDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss,properties = DialogProperties(usePlatformDefaultWidth = false,decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colors.background).testTag("delete-file-dialog"),contentAlignment = Alignment.Center) {
            val nameHeight = maxHeight * 0.18f
            Column(Modifier.width(maxWidth * 0.70f),horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Удалить файл?",style = MaterialTheme.typography.title3,textAlign = TextAlign.Center)
                Box(Modifier.heightIn(max = nameHeight).verticalScroll(rememberScrollState())) {
                    Text(name,style = MaterialTheme.typography.caption2,textAlign = TextAlign.Center)
                }
                Text("Из библиотеки часов",style = MaterialTheme.typography.caption3,color = Color(0xFF9CB1C3),textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = onDismiss,modifier = Modifier.size(46.dp).testTag("cancel-file-delete").semantics { contentDescription = "Отмена" },colors = ButtonDefaults.secondaryButtonColors()) { Text("×",fontSize = 25.sp) }
                        Text("Отмена",style = MaterialTheme.typography.caption3,modifier = Modifier.padding(top = 3.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = onConfirm,modifier = Modifier.size(46.dp).testTag("confirm-file-delete").semantics { contentDescription = "Удалить файл" },colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFAD3348))) {
                            Icon(painterResource(android.R.drawable.ic_menu_delete),contentDescription = null,modifier = Modifier.size(22.dp))
                        }
                        Text("Удалить",style = MaterialTheme.typography.caption3,modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}
