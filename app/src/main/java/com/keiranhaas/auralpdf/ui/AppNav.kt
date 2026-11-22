package com.keiranhaas.auralpdf.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.keiranhaas.auralpdf.ui.screens.*

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(onOpen = { nav.navigate(it) }) }
        composable("merge") { MergeScreen(back) }
        composable("split") { SplitScreen(back) }
        composable("compress") { CompressScreen(back) }
        composable("sign") { SignScreen(back) }
        composable("forms") { FormScreen(back) }
        composable("scan") { ScanScreen(back) }
        composable("text") { TextScreen(back) }
    }
}