package com.serortech.cardia

import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CardiaTheme {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    var screen by remember { mutableStateOf(Screen.CAMERA) }
    when (screen) {
        Screen.CAMERA -> CameraScreen(onSettings = { screen = Screen.SETTINGS })
        Screen.SETTINGS -> {
            BackHandler { screen = Screen.CAMERA }
            SettingsScreen(onBack = { screen = Screen.CAMERA })
        }
    }
}
