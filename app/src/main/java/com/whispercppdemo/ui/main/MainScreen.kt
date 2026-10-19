package com.whispercppdemo.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainScreenViewModel) {
    val clipboard = LocalClipboardManager.current

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.transcribe(uri)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("תמלול והקראה", style = MaterialTheme.typography.headlineSmall)
            Text(viewModel.status, style = MaterialTheme.typography.bodyMedium)
            if (viewModel.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Divider()
            Text("תמלול הודעה קולית", style = MaterialTheme.typography.titleMedium)
            Text(
                "בווטסאפ: לחיצה ארוכה על ההודעה הקולית ← שיתוף ← תמלול והקראה.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = { audioPicker.launch(arrayOf("audio/*", "application/ogg")) },
                enabled = viewModel.modelReady && !viewModel.isBusy
            ) { Text("בחר הקלטה מהטלפון") }

            if (viewModel.transcript.isNotEmpty()) {
                SelectionContainer {
                    Text(viewModel.transcript, style = MaterialTheme.typography.bodyLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { clipboard.setText(AnnotatedString(viewModel.transcript)) }) {
                        Text("העתק")
                    }
                    OutlinedButton(onClick = { viewModel.shareTranscript() }) {
                        Text("שלח כטקסט")
                    }
                    OutlinedButton(onClick = { viewModel.ttsText = viewModel.transcript }) {
                        Text("להקראה")
                    }
                }
            }

            Divider()
            Text("הקראה ושליחה לווטסאפ", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = viewModel.ttsText,
                onValueChange = { viewModel.ttsText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                label = { Text("הטקסט להקראה") }
            )
            Button(
                onClick = { viewModel.speakAndShare() },
                enabled = !viewModel.isBusy
            ) { Text("הקרא ושתף לווטסאפ") }

            Divider()
            OutlinedButton(
                onClick = { modelPicker.launch(arrayOf("*/*")) },
                enabled = !viewModel.isBusy
            ) { Text(if (viewModel.modelReady) "החלף קובץ מודל" else "בחר קובץ מודל") }
        }
    }
}
