package com.music.spotui.data.entity

data class AlbumsModel(
    val id : Int,
    val artists : String,
    val coverUri : String,
    val name : String,
    val time : String,
    // Release kind from Spotify: "album", "single", "compilation"... Empty when unknown.
    val type : String = "",
    // Exact catalog identity; the numeric UI id is a hash and cannot open an album.
    val spotifyId : String = ""
){
    constructor() : this( -1,"" ,"" ,"", "")
}
