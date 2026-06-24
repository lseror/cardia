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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.serortech.cardia.net.CardiaClient
import com.serortech.cardia.settings.SettingsStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { SettingsStore(ctx) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val versionLabel = remember {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "Version ${pi.versionName} (${pi.longVersionCode})"
        }.getOrDefault("Version ?")
    }

    var serverUrl by remember { mutableStateOf(store.serverUrl) }
    var inviteCode by remember { mutableStateOf("") }
    var activated by remember { mutableStateOf(store.licenseKey.isNotBlank()) }
    var dailyLimit by remember { mutableStateOf(store.dailyLimit) }
    var working by remember { mutableStateOf(false) }

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
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("URL du serveur") },
                supportingText = { Text("Ex. https://api.exemple.com/api/cardia — c'est le serveur qui appelle l'IA.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

            if (activated) {
                Text(
                    "Activé ✓" + if (dailyLimit > 0) "  —  $dailyLimit requêtes/jour" else "",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "L'app est enregistrée. Aucune clé IA n'est stockée sur l'appareil.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = {
                        store.serverUrl = serverUrl
                        scope.launch { snackbar.showSnackbar("URL enregistrée") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enregistrer l'URL") }
                TextButton(
                    onClick = {
                        store.clearLicense()
                        activated = false
                        dailyLimit = 0
                        inviteCode = ""
                        scope.launch { snackbar.showSnackbar("Licence réinitialisée") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Réinitialiser la licence") }
            } else {
                OutlinedTextField(
                    value = inviteCode,
                    onValueChange = { inviteCode = it },
                    label = { Text("Code d'invitation") },
                    supportingText = { Text("Fourni par l'administrateur. Active l'app et récupère sa clé de licence.") },
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = !working && serverUrl.isNotBlank() && inviteCode.isNotBlank(),
                    onClick = {
                        working = true
                        store.serverUrl = serverUrl
                        val baseUrl = store.serverUrl // normalisé (trim + sans / final)
                        val code = inviteCode.trim()
                        scope.launch {
                            try {
                                val res = CardiaClient.register(baseUrl, code)
                                store.licenseKey = res.installKey
                                store.dailyLimit = res.dailyLimit
                                dailyLimit = res.dailyLimit
                                activated = true
                                snackbar.showSnackbar("Activé ✓")
                            } catch (e: Exception) {
                                snackbar.showSnackbar(e.message ?: "Échec de l'activation")
                            } finally {
                                working = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (working) "Activation…" else "Activer") }
            }

            Text(
                versionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
