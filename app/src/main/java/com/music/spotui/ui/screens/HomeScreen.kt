package com.music.spotui.ui.screens

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.music.spotui.ui.components.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.theme.GridBackground
import com.music.spotui.ui.viewmodel.HomeViewModel
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.ui.text.style.TextOverflow
import com.music.spotui.data.entity.HomeSectionIds
import com.music.spotui.data.entity.HomeSectionType
import java.time.LocalTime


@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeScreen(navController: NavController){

    val homeViewModel : HomeViewModel = hiltViewModel()
    val playerViewModel : PlayerViewModel = hiltViewModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    val home by homeViewModel.home.collectAsState()
    val albums by homeViewModel.albums.collectAsState()
    val artists by homeViewModel.artists.collectAsState()
    val whitelistVersion by com.music.spotui.util.KosherWhitelistManager.versionState

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                homeViewModel.refreshHome(force = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .statusBarsPadding()
    ) {
        val feed = (home as? Response.Success)?.data
        // Each feed resolves independently — albums (new releases) often succeeds
        // while artists (personalized) gets rate-limited. Render whatever arrived
        // instead of casting blindly (which crashed when one feed was an Error).
        val albumsList = (albums as? Response.Success)?.data.orEmpty()
        val artistsList = (artists as? Response.Success)?.data.orEmpty()

        when {
            // Preferred: the real personalized Spotify home feed.
            feed != null && feed.sections.isNotEmpty() -> {
                HomeFeedContent(navController, feed) { song ->
                    playerViewModel.updateQueue(listOf(song))
                    SongPlayer.playSong(song.url, context)
                }
            }

            // Still resolving the real personalized home feed. Show the loader even
            // if the new-releases/artists fallbacks already arrived from cache —
            // otherwise the old "Sum up" layout flashes for a beat before the real
            // Spotify-style feed swaps in.
            home is Response.Loading -> {
                Loader()
            }

            // Fallback: home feed errored but new-releases / artists came through.
            albumsList.isNotEmpty() || artistsList.isNotEmpty() -> {
                SumUpHomeScreen(navController = navController, albums = albumsList, artists = artistsList)
            }

            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Couldn't load music.\nCheck your connection and try again.",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun onHomeItemClick(
    navController: NavController,
    item: HomeItem,
    onPlaySong: ((SongsModel) -> Unit)? = null
) {
    when (item) {
        is HomeItem.Album -> navController.navigate(albumRoute(item.name, item.artists.ifBlank { item.subtitle }, item.imageUrl))
        is HomeItem.Artist -> navController.navigate(artistRoute(item.name, item.id))
        is HomeItem.Playlist ->
            if (item.id.isNotBlank()) navController.navigate(playlistRoute(item.id, item.name))
            else navController.navigate(albumRoute(item.name, cover = item.imageUrl))
        is HomeItem.Track -> {
            val albumOrTitle = item.song.album.ifBlank { item.song.title }
            navController.navigate(albumRoute(albumOrTitle, item.song.singer, item.song.coverUri))
        }
        is HomeItem.LikedSongs -> navController.navigate(Routes.Liked.route)
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeFeedContent(
    navController: NavController,
    feed: HomeFeedModel,
    onPlaySong: ((SongsModel) -> Unit)? = null
) {
    val topGridItems = feed.topGrid.take(4)
    val sections = feed.sections

    LazyColumn(
        contentPadding = PaddingValues(bottom = 130.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        item {
            HomeHeaderRow(navController)
        }
        if (topGridItems.isNotEmpty()) {
            item {
                HomeTopGrid(navController, topGridItems, onPlaySong)
            }
        }
        items(sections.size, key = { i -> sections[i].id.ifBlank { "sec_$i" } }) { i ->
            HomeFeedSection(navController, sections[i], onPlaySong)
        }
    }
}

/** Spotify-style top row: profile avatar on the left, filter pills on the right. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeHeaderRow(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.music.spotui.data.api.ProfileCache.ensure(context)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 26.dp, bottom = 8.dp),
    ) {
        val avatarUrl = com.music.spotui.data.api.ProfileCache.imageUrl
        val initial = com.music.spotui.data.api.ProfileCache.name
            ?.trim()?.firstOrNull()?.uppercase() ?: "•"
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(start = 16.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFFE8622C))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { navController.navigate(Routes.Settings.route) },
        ) {
            if (avatarUrl != null) {
                GlideImage(
                    model = avatarUrl,
                    contentScale = ContentScale.Crop,
                    contentDescription = "Profile",
                    modifier = Modifier.size(34.dp),
                )
            } else {
                Text(
                    text = initial,
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        val filters = listOf("הכול", "מוזיקה", "פודקאסטים")
        var selected by remember { androidx.compose.runtime.mutableStateOf("הכול") }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(filters.size) { i ->
                val label = filters[i]
                val isSel = label == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isSel) Color(0xFF1ED760) else Color(0xFF2A2A2A))
                        .clickable {
                            selected = label
                            if (label == "פודקאסטים") {
                                navController.navigate(Routes.Search.route)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = label,
                        color = if (isSel) Color.Black else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** Exactly 4 shortcut buttons (2x2 grid) for recently played items */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeTopGrid(
    navController: NavController,
    items: List<HomeItem>,
    onPlaySong: ((SongsModel) -> Unit)? = null
) {
    val four = items.take(4)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        four.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { item ->
                    HomeGridCard(
                        navController = navController,
                        item = item,
                        modifier = Modifier.weight(1f),
                        onPlaySong = onPlaySong
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeGridCard(
    navController: NavController,
    item: HomeItem,
    modifier: Modifier = Modifier,
    onPlaySong: ((SongsModel) -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF282828))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onHomeItemClick(navController, item, onPlaySong) }
    ) {
        GlideImage(
            modifier = Modifier.size(56.dp),
            contentScale = ContentScale.Crop,
            model = item.imageUrl,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            isAllowed = com.music.spotui.util.KosherWhitelistManager.isHomeItemWhitelisted(item),
            contentDescription = item.name
        )
        Text(
            text = item.name,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun HomeFeedSection(
    navController: NavController,
    section: HomeSection,
    onPlaySong: ((SongsModel) -> Unit)? = null
) {
    if (section.id == HomeSectionIds.SIMILAR_ARTISTS || section.headerArtist != null) {
        ArtistSectionHeader(section) {
            section.headerArtist?.let { artist ->
                navController.navigate(artistRoute(artist.name, artist.id))
            }
        }
    } else {
        Text(
            text = section.title,
            color = Color.White,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)
        )
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(section.items.size) { i ->
            val item = section.items[i]
            when {
                item is HomeItem.LikedSongs -> {
                    LikedSongsCard(count = item.count) {
                        onHomeItemClick(navController, item, onPlaySong)
                    }
                }
                section.type == HomeSectionType.ARTISTS && item is HomeItem.Artist -> {
                    HomeArtistCircleCard(artist = item) {
                        onHomeItemClick(navController, item, onPlaySong)
                    }
                }
                else -> {
                    HomeFeedCard(item = item) {
                        onHomeItemClick(navController, item, onPlaySong)
                    }
                }
            }
        }
    }
}

/** Header with artist avatar next to "אמנים נוספים כמו" */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ArtistSectionHeader(
    section: HomeSection,
    onArtistClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onArtistClick() }
    ) {
        val artist = section.headerArtist
        if (artist != null && artist.coverUri.isNotBlank()) {
            GlideImage(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                model = artist.coverUri,
                loading = placeholder(R.drawable.placeholder),
                failure = placeholder(R.drawable.placeholder),
                isAllowed = com.music.spotui.util.KosherWhitelistManager.isArtistModelWhitelisted(artist),
                contentDescription = artist.name
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.subtitle ?: "אמנים נוספים כמו",
                color = Color(0xFFB3B3B3),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = section.title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Pinned card for "שירים שאהבתם" in Recently Played */
@Composable
private fun LikedSongsCard(count: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(148.dp)
            .padding(6.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF450AF5),
                            Color(0xFF8E8EE5),
                            Color(0xFFC4B5FD)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = "Liked Songs",
                tint = Color.White,
                modifier = Modifier.size(54.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "שירים שאהבתם",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 2.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1ED760))
            ) {
                Text(
                    text = "✓",
                    color = Color.Black,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (count == 1) "נוסף שיר 1" else "$count שירים",
                color = Color(0xFFB3B3B3),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Circular card for "אמנים פופולריים" */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeArtistCircleCard(artist: HomeItem.Artist, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(140.dp)
            .padding(6.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    ) {
        GlideImage(
            modifier = Modifier
                .size(130.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
            model = artist.imageUrl,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            isAllowed = com.music.spotui.util.KosherWhitelistManager.isHomeItemWhitelisted(artist),
            contentDescription = artist.name
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeFeedCard(item: HomeItem, onClick: () -> Unit) {
    val isArtist = item is HomeItem.Artist
    val subtitle = when (item) {
        is HomeItem.Album -> item.subtitle
        is HomeItem.Playlist -> item.subtitle
        is HomeItem.Artist -> "Artist"
        is HomeItem.Track -> item.subtitle
        is HomeItem.LikedSongs -> "${item.count} שירים"
    }
    Column(
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier
            .width(148.dp)
            .padding(6.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        GlideImage(
            modifier = Modifier
                .size(148.dp)
                .clip(if (isArtist) CircleShape else RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            model = item.imageUrl,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            isAllowed = com.music.spotui.util.KosherWhitelistManager.isHomeItemWhitelisted(item),
            contentDescription = item.name,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
        Text(
            text = subtitle,
            color = Color(0xFFB3B3B3),
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun SumUpHomeScreen(navController : NavController, albums: List<AlbumsModel>, artists: List<ArtistsModel>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color(AppBackground.toArgb()))
    ){

        GreetingSection()
        //ChipSection(chip = listOf(" All ", "Music", "Podcasts"))

        if (albums.isNotEmpty()) {
            HomePlaylistGrid(navController, albums)
            HomeAlbums(album = albums, navController)
        }
        //HomeRecentlyPlayed(navController, albums = listOf("karan aujla", "diljit", "fudfu", "frref", "frrf"))
        if (artists.isNotEmpty()) {
            HomeArtists(artists = artists, navController)
        }
        if (albums.isNotEmpty()) {
            ImageCard(navController, albums)
        }
    }
}



@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun GreetingSection(name : String = "User") {
    val currentHour = LocalTime.now().hour
    val greeting = when {
        currentHour < 12 -> "Good Morning"
        currentHour < 17 -> "Good Afternoon"
        else -> "Good Evening"
    }
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
                )
            Text(
                text = "Have a Nice Day",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontSize = 13.sp
                )
        }
//        Icon(imageVector = Icons.Outlined.Person, contentDescription = "Profile", tint = Color.White)
    }
}

//@Composable
//fun ChipSection(
//    chip : List<String>
//) {
//    var selectedChip by remember {
//        mutableStateOf(0)
//    }
//    LazyRow{
//        items(chip.size){
//            Box(contentAlignment = Alignment.Center,
//                modifier = Modifier
//                    .padding(15.dp, 0.dp, 0.dp, 0.dp)
//                    .clickable {
//                        selectedChip = it
//                    }
//                    .clip(RoundedCornerShape(50.dp))
//                    .background(
//                        if (selectedChip == it) Color.Green
//                        else Color.Gray
//                    )
//                    .padding(10.dp, 5.dp)
//
//            ){
//                Text(text = chip[it], color = Color.White)
//            }
//        }
//    }
//}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun HomePlaylistGrid(navController: NavController, albums: List<AlbumsModel>) {
    // Use up to 8 albums, but don't assume there are at least 8 (rate-limited /
    // small feeds can return fewer) — that previously caused IndexOutOfBounds.
    val gridAlbums = albums.take(8)

    val chunkedAlbums = gridAlbums.chunked(2)
    Log.d("giveme", chunkedAlbums.toString())
    Column(
        modifier = Modifier
            .padding(0.dp, 10.dp)
    ){
        repeat(chunkedAlbums.size){
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .padding(15.dp, 5.dp, 7.dp, 0.dp)
                    .fillMaxWidth()
            )
            {
                repeat(chunkedAlbums[it].size){ album ->
                    Row(
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(2.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(GridBackground.toArgb()))
                            .width(180.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                val albumModel = chunkedAlbums[it][album]
                                Log.d("check", albumModel.name)
                                navController.navigate(albumRoute(albumModel.name, albumModel.artists, albumModel.coverUri))
                            }
                    ) {
                        val albItem = chunkedAlbums[it][album]
                        GlideImage(modifier = Modifier
                            .size(55.dp),
                            contentScale = ContentScale.Crop,
                            model = albItem.coverUri,
                            loading = placeholder(R.drawable.placeholder),
                            failure = placeholder(R.drawable.placeholder),
                            isAllowed = com.music.spotui.util.KosherWhitelistManager.isAlbumWhitelisted(albItem),
                            contentDescription = "Profile")
                        Text(modifier = Modifier.padding(5.dp),
                            text = chunkedAlbums[it][album].name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                    }
                }

            }
        }
    }
}


@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun HomeAlbums(
    album : List<AlbumsModel>,
    navController: NavController
) {
    val reversedAlbum = album.reversed().dropLast(1)
    Text(modifier = Modifier
        .padding(20.dp, 10.dp, 0.dp, 0.dp),
        text = "Albums",
        color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold)
        LazyRow(modifier = Modifier.padding(6.dp)){
            items(reversedAlbum.size){ album ->
                Box(modifier = Modifier
                    .padding(10.dp)
                    .width(150.dp)
                    .height(195.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        navController.navigate(albumRoute(reversedAlbum[album].name, reversedAlbum[album].artists, reversedAlbum[album].coverUri))
                    }
            ){
                Column(
                    horizontalAlignment = Alignment.Start,
                    ) {

                    val albItem = reversedAlbum[album]
                    GlideImage(modifier = Modifier
                        .size(150.dp),
                        contentScale = ContentScale.Crop,
                        model = albItem.coverUri,
                        loading = placeholder(R.drawable.placeholder),
                        failure = placeholder(R.drawable.placeholder),
                        isAllowed = com.music.spotui.util.KosherWhitelistManager.isAlbumWhitelisted(albItem),
                        contentDescription = "Albums")
                    Text(
                        fontSize = 13.sp,
                        text = reversedAlbum[album].name,
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        fontWeight = FontWeight.Bold)
                    Text(
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        text = reversedAlbum[album].artists,
                        color = Color.LightGray)
                }

            }
        }
    }
}

@Composable
fun HomeRecentlyPlayed(
    navController: NavController,
    albums : List<String>
) {
    Text(modifier = Modifier
        .padding(20.dp, 10.dp, 0.dp, 0.dp),
        text = "Recently Played",
        color = Color.White,
        fontSize = 23.sp,
        fontWeight = FontWeight.Bold)
    LazyRow(modifier = Modifier.padding(6.dp)){
        items(albums.size){
            Box(modifier = Modifier
                .padding(10.dp)
                .width(130.dp)
                .height(140.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    navController.navigate(Routes.Player.route)
                }
            ){
                Column(horizontalAlignment = Alignment.Start) {
                    Image(modifier = Modifier
                        .size(120.dp)
                        .background(Color.Green),
                        contentScale = ContentScale.Crop,
                        painter = painterResource(id = R.drawable.album),
                        contentDescription = "Albums")
                    Text(modifier = Modifier.padding(2.dp),
                        text = "Album name",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp)
                }

            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun HomeArtists(
    artists : List<ArtistsModel>,
    navController: NavController
) {
    Text(modifier = Modifier
        .padding(20.dp, 10.dp, 0.dp, 0.dp),
        text = "Best of Artists",
        color = Color.White,
        fontSize = 23.sp,
        fontWeight = FontWeight.Bold)
    LazyRow(modifier = Modifier.padding(6.dp)){
        items(artists.size){artist ->
            Box(modifier = Modifier
                .padding(10.dp)
                .width(150.dp)
                .height(200.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    Log.d("check", artists[artist].name)
                    navController.navigate(artistRoute(artists[artist].name, artists[artist].id))
                }
            ){
                Column(horizontalAlignment = Alignment.Start) {



                    val artistItem = artists[artist]
                    GlideImage(modifier = Modifier
                        .size(150.dp),
                        contentScale = ContentScale.Crop,
                        model = artistItem.coverUri,
                        loading = placeholder(R.drawable.placeholder),
                        failure = placeholder(R.drawable.placeholder),
                        isAllowed = com.music.spotui.util.KosherWhitelistManager.isArtistModelWhitelisted(artistItem),
                        contentDescription = "Albums")
                    Text(modifier = Modifier.padding(2.dp),
                        text = "This is ${artists[artist].name}",
                        color = Color.LightGray,
                        fontSize = 11.sp)
                }

            }
        }
    }
}


@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ImageCard(
    navController: NavController,
    allAlbums: List<AlbumsModel>,
    modifier: Modifier = Modifier
) {

    val albums = allAlbums.takeLast(3)
    Text(modifier = Modifier
        .padding(20.dp, 10.dp, 0.dp, 0.dp),
        text = "Discover",
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold)
    Column(
        modifier = Modifier.padding(0.dp, 10.dp, 0.dp, 50.dp)
    ) {
        repeat(albums.size) { album ->
            Card(
                shape = RoundedCornerShape(15.dp),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 5.dp
                ),
                modifier = Modifier
                    .padding(15.dp)
                    .fillMaxWidth()
                    .height(380.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        navController.navigate(albumRoute(albums[album].name, albums[album].artists, albums[album].coverUri))
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    val albumItem = albums[album]
                    GlideImage(
                        modifier = Modifier.fillMaxSize(),
                        model = albumItem.coverUri,
                        contentDescription = "artists",
                        loading = placeholder(R.drawable.placeholder),
                        failure = placeholder(R.drawable.placeholder),
                        isAllowed = com.music.spotui.util.KosherWhitelistManager.isAlbumWhitelisted(albumItem),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color(AppBackground.toArgb())
                                    ),
                                    startY = 150f
                                )
                            )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(0.dp, 0.dp, 0.dp, 30.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Text(
                            text = "Album : ${albums[album].name}",
                            style = TextStyle(color = Color.White, fontSize = 20.sp),
                            textAlign = TextAlign.Center
                        )

                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(100.dp))
    }
}





















