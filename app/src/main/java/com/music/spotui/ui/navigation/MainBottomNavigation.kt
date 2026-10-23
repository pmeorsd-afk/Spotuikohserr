package com.music.spotui.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.music.spotui.ui.components.MiniPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

import androidx.compose.foundation.layout.navigationBarsPadding

class NoRippleInteractionSource : MutableInteractionSource {

    override val interactions: Flow<Interaction> = emptyFlow()

    override suspend fun emit(interaction: Interaction) {}

    override fun tryEmit(interaction: Interaction) = true
}

@Composable
fun MainBottomNavigation(navController: NavHostController, bottomBarState: MutableState<Boolean>, bottomBarPlayerState : MutableState<Boolean>) {

    val navItems = listOf(
        Routes.Home,
        Routes.Search,
        Routes.Library
    )
    AnimatedVisibility(
        visible = bottomBarState.value,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        content = {
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black
                            ),
                            startY = 0f
                        )
                    )
            ) {

                Column(modifier = Modifier.navigationBarsPadding()) {

                    AnimatedVisibility(
                        visible = bottomBarPlayerState.value,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it }),
                        content = {
                            MiniPlayer(navController)
                        }
                    )



                    NavigationBar(
                        modifier = Modifier
                            .offset(y = 10.dp)
                            .padding(30.dp, 0.dp)
                            .fillMaxWidth(),
                        containerColor = Color.Transparent,
                        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
                    ) {
                        val navStack by navController.currentBackStackEntryAsState()
                        val currentRoute = navStack?.destination?.route

                        navItems.forEach { item ->
                            val isSelected = currentRoute == item.route
                            val iconRes = if (isSelected) item.selectedIcon else item.icon
                            val iconScale = remember(item.route) { Animatable(1f) }
                            var tapCount by remember(item.route) { mutableIntStateOf(0) }
                            LaunchedEffect(tapCount) {
                                if (tapCount != 0) {
                                    // Complete the pulse even for a quick tap or a tap on the active tab.
                                    iconScale.animateTo(0.88f, tween(80, easing = FastOutSlowInEasing))
                                    iconScale.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
                                }
                            }
                            NavigationBarItem(
                                selected = isSelected,
                                icon = {
                                    Icon(
                                        painter = painterResource(
                                            id = iconRes
                                        ), contentDescription = item.label,
                                        modifier = Modifier.graphicsLayer {
                                            scaleX = iconScale.value
                                            scaleY = iconScale.value
                                        }
                                    )
                                },
                                label = {
                                    Text(
                                        color = if (isSelected) Color.White else Color(0xFFB3B3B3),
                                        text = item.label,
                                        fontSize = 11.sp
                                    )
                                },
                                onClick = {
                                    tapCount++
                                    if (item == Routes.Home) {
                                        com.music.spotui.ui.viewmodel.HomeRefreshSignal.trigger(selectAll = true)
                                    }
                                    navController.navigate(item.route) {
                                        navController.graph.startDestinationRoute?.let {
                                            popUpTo(item.route)
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                alwaysShowLabel = true,
                                interactionSource = NoRippleInteractionSource(),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.White,
                                    unselectedIconColor = Color(0xFFB3B3B3),
                                    selectedTextColor = Color.White,
                                    unselectedTextColor = Color(0xFFB3B3B3),
                                    indicatorColor = Color.Transparent
                                )
                            )

                        }
                    }



                }



            }
        }
    )
}
