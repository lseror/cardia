package com.serortech.cardia.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.serortech.cardia.settings.ApiKeyStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { ApiKeyStore(ctx) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var openAiKey by remember { mutableStateOf(store.openAiKey) }
    var showKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = openAiKey,
                onValueChange = { openAiKey = it },
                label = { Text("Clé OpenAI") },
                supportingText = { Text("Pour la détection de carte (vision).") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (showKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Cacher" else "Voir")
                    }
                },
            )
            Button(
                onClick = {
                    store.openAiKey = openAiKey
                    scope.launch { snackbar.showSnackbar("Clé enregistrée") }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enregistrer") }
        }
    }
}
