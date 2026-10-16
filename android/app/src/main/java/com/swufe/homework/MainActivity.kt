package com.swufe.homework

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.swufe.homework.ui.nav.AppNavHost
import com.swufe.homework.ui.theme.SwufeTheme
import dagger.hilt.android.AndroidEntryPoint

/** 单 Activity；所有页面在 [AppNavHost] 里。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SwufeTheme { AppNavHost() } }
    }
}
