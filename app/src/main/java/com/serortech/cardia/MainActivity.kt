package com.serortech.cardia

import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.serortech.cardia.ui.CameraScreen
import com.serortech.cardia.ui.SettingsScreen
import com.serortech.cardia.ui.theme.CardiaTheme

private enum class Screen { CAMERA, SETTINGS }

class MainActivity : ComponentActivity() {
    /**
     * Bascule le flash de la caméra active. Fournie par [CameraScreen] quand la
     * caméra est liée, remise à null quand elle quitte la composition (passage en
     * Réglages, etc.) — Vol+ est alors sans effet.
     */
    private var torchToggle: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepScreenAwakeAtFixedBrightness()
        setContent {
            CardiaTheme {
                App(onProvideTorchToggle = { torchToggle = it })
            }
        }
    }

    /**
     * Tant que l'activity est visible : pas de mise en veille ni d'auto-dim de
     * l'écran ([WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]), et luminosité
     * figée sur la valeur système courante pour que l'auto-luminosité (capteur
     * ambiant) ne la fasse plus varier.
     */
    private fun keepScreenAwakeAtFixedBrightness() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val current = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Settings.SettingNotFoundException) {
            -1
        }
        if (current >= 0) {
            window.attributes = window.attributes.apply {
                // 0..255 (réglage système) → 0f..1f (fenêtre).
                screenBrightness = current.coerceIn(1, 255) / 255f
            }
        }
    }

    /**
     * Vol+ bascule le flash allumé/éteint en continu (pas de stroboscope). On ne
     * réagit qu'au premier appui ([KeyEvent.getRepeatCount] == 0) et on consomme
     * l'évènement pour ne pas déclencher le volume système.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0) torchToggle?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Consomme aussi le key-up de Vol+ pour neutraliser le volume système. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) return true
        return super.onKeyUp(keyCode, event)
    }
}

@Composable
private fun App(onProvideTorchToggle: (toggle: (() -> Unit)?) -> Unit) {
    var screen by remember { mutableStateOf(Screen.CAMERA) }
    when (screen) {
        Screen.CAMERA -> CameraScreen(
            onSettings = { screen = Screen.SETTINGS },
            onProvideTorchToggle = onProvideTorchToggle,
        )
        Screen.SETTINGS -> {
            BackHandler { screen = Screen.CAMERA }
            SettingsScreen(onBack = { screen = Screen.CAMERA })
        }
    }
}
