package com.music.spotui.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.music.spotui.data.entity.PodcastCategoriesData
import com.music.spotui.ui.components.GlideImage
import com.music.spotui.ui.viewmodel.PodcastChooserViewModel
import com.music.spotui.util.KosherWhitelistManager

@Composable
fun PodcastChooserScreen(navController: NavController, vm: PodcastChooserViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val whitelist by KosherWhitelistManager.versionState
    val categories = PodcastCategoriesData.curatedCategories
    val category = categories.find { it.id == state.category }
    LaunchedEffect(state.saved) { if (state.saved) navController.popBackStack() }
    BackHandler(state.category != null) { vm.category(null) }

    Column(Modifier.fillMaxSize().background(Color(0xFF10130E)).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (state.category != null) vm.category(null) else navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה", tint = Color.White)
            }
            Text(category?.title ?: "בחירת פודקאסטים", color = Color.White, fontSize = 26.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(vertical = 20.dp))
        }
        OutlinedTextField(
            value = state.query, onValueChange = vm::search,
            placeholder = { Text("חיפוש פודקאסטים") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                focusedTextColor = Color.Black, unfocusedTextColor = Color.Black,
                focusedLeadingIconColor = Color.Black, unfocusedLeadingIconColor = Color.Black,
                focusedPlaceholderColor = Color.DarkGray, unfocusedPlaceholderColor = Color.DarkGray,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        val grid = rememberLazyGridState()
        LaunchedEffect(state.query, state.category) { grid.scrollToItem(0) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp), state = grid,
            modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(state.shows, key = { "show:${it.id}" }) { show ->
                val selected = show.id in state.selected
                val allowed = remember(show, whitelist) { KosherWhitelistManager.isPodcastShowWhitelisted(show) }
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { vm.toggle(show) })) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                        .then(if (selected) Modifier.border(2.dp, Color(0xFF1ED760), RoundedCornerShape(10.dp)) else Modifier)) {
                        GlideImage(model = show.coverUri, isAllowed = allowed, contentDescription = show.name, modifier = Modifier.fillMaxSize())
                        if (selected) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(24.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Check, "נבחר", tint = Color.Black, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(show.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (state.category == null && state.query.isBlank()) {
                items(categories, key = { "category:${it.id}" }) { item ->
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                        .background(Color(item.colorHex)).clickable { vm.category(item.id) }.padding(8.dp),
                        contentAlignment = Alignment.Center) {
                        Text("עוד בקטגוריה\n${item.title}", color = Color.White, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, fontSize = 14.sp)
                    }
                }
            }
            if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF1ED760), modifier = Modifier.size(28.dp))
                }
            }
            if (state.error) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("לא הצלחנו לטעון או לשמור. הבחירות שלך נשמרות במסך.", color = Color.White, textAlign = TextAlign.Center)
                    TextButton(onClick = vm::retry) { Text("ניסיון נוסף", color = Color(0xFF1ED760)) }
                }
            }
            if (!state.loading && !state.error && state.shows.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("לא נמצאו פודקאסטים", color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(20.dp))
            }
            if (state.hasMore && !state.loading) item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = vm::loadMore) { Text("פודקאסטים נוספים", color = Color.White) }
            }
        }
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            Button(onClick = vm::save, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                contentPadding = PaddingValues(horizontal = 30.dp, vertical = 14.dp)) {
                Text("סיום (${state.selected.size})", fontWeight = FontWeight.Bold)
            }
        }
    }
}
