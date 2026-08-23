package com.example.transpose

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import com.example.main.MainScreen
import com.example.ui.theme.TransposeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        CrashDiagnostics.markStage(newBase, "main_attach_base")
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashDiagnostics.markStage(this, "main_oncreate_enter")
        super.onCreate(savedInstanceState)
        CrashDiagnostics.markStage(this, "main_after_super")

        CrashDiagnostics.markStage(this, "main_before_setcontent")
        setContent {
            CrashDiagnostics.markStage(this, "main_compose_enter")
            TransposeTheme {
                LaunchedEffect(Unit) {
                    CrashDiagnostics.markStage(this@MainActivity, "main_composed")
                }
                MainScreen()
            }
        }
        CrashDiagnostics.markStage(this, "main_after_setcontent")
    }
}
