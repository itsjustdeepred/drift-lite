package com.eddyizm.tempus.ui.components

import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.components.DriftColors

private val LAcc get() = com.eddyizm.tempus.helper.DriftAccent.compose
private val LHi get() = DriftColors.text
private val LLo get() = DriftColors.textLo
private val LCard get() = DriftColors.card

/** Name a playlist: [taken] marks names already used and disables the confirm button. */
@Composable
fun NameDialog(title: String, value: String, onValue: (String) -> Unit, confirm: String, onDismiss: () -> Unit,
               taken: (String) -> Boolean = { false }, onConfirm: () -> Unit) {
    val clash = taken(value)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DriftColors.surface,
        title = { Text(title, color = LHi) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(LCard).padding(14.dp)) {
                    if (value.isEmpty()) Text(stringResource(R.string.lite_playlist_name), color = LLo, fontSize = 16.sp)
                    BasicTextField(value, { onValue(it.take(60)) }, singleLine = true, textStyle = TextStyle(color = LHi, fontSize = 16.sp),
                        cursorBrush = SolidColor(LAcc), modifier = Modifier.fillMaxWidth())
                }
                if (clash) Text(stringResource(R.string.lite_name_taken), color = DriftColors.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !clash) { Text(confirm, color = if (clash) LLo else com.eddyizm.tempus.helper.DriftAccent.composeText, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lite_cancel), color = LLo) } },
    )
}

