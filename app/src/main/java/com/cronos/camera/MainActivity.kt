package com.cronos.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.cronos.camera.ui.CronosApp
import com.cronos.camera.ui.theme.CronosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CronosTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0F14)) {
                    CronosApp()
                }
            }
        }
    }
}
