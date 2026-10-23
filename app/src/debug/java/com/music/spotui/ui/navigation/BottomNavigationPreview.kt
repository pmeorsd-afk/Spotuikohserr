package com.music.spotui.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.music.spotui.ui.theme.SpotuiTheme

@Preview(name = "Bottom navigation normal", widthDp = 360, heightDp = 160)
@Preview(name = "Bottom navigation small", widthDp = 240, heightDp = 160)
@Preview(name = "Bottom navigation large text", widthDp = 240, heightDp = 160, fontScale = 1.3f)
@Composable
private fun BottomNavigationPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            val navController = rememberNavController()
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.BottomCenter) {
                NavHost(navController, startDestination = Routes.Home.route) {
                    composable(Routes.Home.route) {}
                    composable(Routes.Search.route) {}
                    composable(Routes.Library.route) {}
                }
                MainBottomNavigation(navController, remember { mutableStateOf(true) }, remember { mutableStateOf(false) })
            }
        }
    }
}
