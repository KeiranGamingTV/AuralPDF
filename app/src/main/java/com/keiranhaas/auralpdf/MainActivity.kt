package com.keiranhaas.auralpdf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.keiranhaas.auralpdf.ui.AppNav
import com.keiranhaas.auralpdf.ui.theme.AuralPdfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AuralPdfTheme { AppNav() } }
    }
}