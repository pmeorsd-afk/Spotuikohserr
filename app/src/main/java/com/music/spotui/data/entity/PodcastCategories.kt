package com.music.spotui.data.entity

/**
 * Verified podcast categories and topic taxonomy derived from Spotify's API and official UX.
 */
data class PodcastCategory(
    val id: String,
    val title: String,
    val englishTopic: String,
    val colorHex: Long,
    val discoveryQueries: List<String>,
    val matchingTopics: List<String> = emptyList(),
    val group: String = "אמנויות ובידור",
    val query: String = discoveryQueries.firstOrNull().orEmpty(),
)

object PodcastCategoriesData {

    // 9 Curated top categories displayed on the main Podcast Portal (Hub)
    val curatedCategories = listOf(
        PodcastCategory(
            id = "educational",
            title = "לימודי",
            englishTopic = "Educational",
            colorHex = 0xFF477D95,
            discoveryQueries = listOf("חינוך", "לימוד", "educational", "ידע"),
            matchingTopics = listOf("Education", "Educational", "Courses"),
            group = "חינוך וידע",
        ),
        PodcastCategory(
            id = "documentary",
            title = "דוקומנטרי",
            englishTopic = "Documentary",
            colorHex = 0xFF503750,
            discoveryQueries = listOf("דוקומנטרי", "documentary", "תעודה", "תחקיר"),
            matchingTopics = listOf("Documentary"),
            group = "חינוך וידע",
        ),
        PodcastCategory(
            id = "comedy",
            title = "קומדיה",
            englishTopic = "Comedy",
            colorHex = 0xFFE13300,
            discoveryQueries = listOf("קומדיה", "comedy", "סטנדאפ", "הומור"),
            matchingTopics = listOf("Comedy"),
            group = "אמנויות ובידור",
        ),
        PodcastCategory(
            id = "pop_culture",
            title = "תרבות פופ",
            englishTopic = "Culture",
            colorHex = 0xFFE8115B,
            discoveryQueries = listOf("תרבות פופ", "תרבות", "בידור", "pop culture"),
            matchingTopics = listOf("Culture", "Society"),
            group = "אמנויות ובידור",
        ),
        PodcastCategory(
            id = "fitness_nutrition",
            title = "כושר ותזונה",
            englishTopic = "Health",
            colorHex = 0xFF1E8555,
            discoveryQueries = listOf("כושר", "תזונה", "בריאות", "health"),
            matchingTopics = listOf("Health", "Fitness", "Nutrition"),
            group = "בריאות וסגנון חיים",
        ),
        PodcastCategory(
            id = "celebrities",
            title = "סלבריטאים",
            englishTopic = "Celebrities",
            colorHex = 0xFF8C736B,
            discoveryQueries = listOf("סלבריטאים", "אירוח", "ראיונות", "celebrities"),
            matchingTopics = listOf("Celebrities", "Entertainment"),
            group = "אמנויות ובידור",
        ),
        PodcastCategory(
            id = "gaming",
            title = "משחקי וידאו",
            englishTopic = "Gaming",
            colorHex = 0xFF1D75DE,
            discoveryQueries = listOf("גיימינג", "משחקי וידאו", "gaming", "משחקים"),
            matchingTopics = listOf("Gaming", "Video Games"),
            group = "אמנויות ובידור",
        ),
        PodcastCategory(
            id = "cinema",
            title = "קולנוע",
            englishTopic = "Film",
            colorHex = 0xFF283EA3,
            discoveryQueries = listOf("קולנוע", "סרטים", "טלוויזיה", "film"),
            matchingTopics = listOf("Film", "TV", "Television"),
            group = "אמנויות ובידור",
        ),
        PodcastCategory(
            id = "books",
            title = "ספרים",
            englishTopic = "Stories",
            colorHex = 0xFF473448,
            discoveryQueries = listOf("ספרים", "סיפורים", "ספרות", "books"),
            matchingTopics = listOf("Books", "Stories", "Literature"),
            group = "אמנויות ובידור",
        ),
    )

    // Full catalog of categories grouped by domain (displayed in "כל הקטגוריות")
    val allGroupedCategories = mapOf(
        "אמנויות ובידור" to listOf(
            PodcastCategory("entertainment", "אמנויות ובידור", "Entertainment", 0xFF8400E7, listOf("בידור", "תרבות", "entertainment"), listOf("Entertainment", "Arts")),
            PodcastCategory("celebrities", "סלבריטאים", "Celebrities", 0xFF8C736B, listOf("סלבריטאים", "אירוח", "ראיונות", "celebrities"), listOf("Celebrities", "Entertainment")),
            PodcastCategory("comedy", "קומדיה", "Comedy", 0xFFE13300, listOf("קומדיה", "comedy", "סטנדאפ", "הומור"), listOf("Comedy")),
            PodcastCategory("fiction", "סיפורת", "Stories", 0xFF473448, listOf("סיפורת", "ספרות", "בדיוני", "fiction"), listOf("Fiction", "Stories")),
            PodcastCategory("cinema", "סרטים וטלוויזיה", "Film", 0xFF283EA3, listOf("קולנוע", "סרטים", "טלוויזיה", "film"), listOf("Film", "TV", "Television")),
            PodcastCategory("music", "מוזיקה", "Music", 0xFFDC148C, listOf("מוזיקה", "שירים", "music", "אלבומים"), listOf("Music", "Music Commentary")),
            PodcastCategory("pop_culture", "תרבות הפופ", "Culture", 0xFFE8115B, listOf("תרבות פופ", "תרבות", "בידור", "pop culture"), listOf("Culture", "Society")),
            PodcastCategory("stories", "סיפורים", "Stories", 0xFFBA5D07, listOf("סיפורים", "ספרים", "אגדות", "stories"), listOf("Stories", "Literature")),
            PodcastCategory("gaming", "משחקי וידאו", "Gaming", 0xFF1D75DE, listOf("גיימינג", "משחקי וידאו", "gaming"), listOf("Gaming", "Video Games")),
        ),
        "בריאות וסגנון חיים" to listOf(
            PodcastCategory("lifestyle", "סגנון חיים", "Society", 0xFF006450, listOf("סגנון חיים", "lifestyle", "חברה"), listOf("Society", "Lifestyle", "Relationships")),
            PodcastCategory("fitness_nutrition", "כושר ותזונה", "Health", 0xFF1E8555, listOf("כושר", "תזונה", "בריאות", "health"), listOf("Health", "Fitness", "Nutrition")),
            PodcastCategory("food", "אוכל", "Food", 0xFFE91429, listOf("אוכל", "קולינריה", "בישול", "food"), listOf("Food", "Cooking")),
            PodcastCategory("meditation", "פודקאסטים למדיטציה", "Self-help", 0xFF148A08, listOf("מדיטציה", "מיינדפולנס", "meditation", "רוגע"), listOf("Self-help", "Health", "Religion & Spirituality")),
            PodcastCategory("parenting", "הורות", "Parenting", 0xFF431F75, listOf("הורות", "משפחה", "ילדים", "parenting"), listOf("Parenting", "Family", "Kids & Family")),
        ),
        "חינוך וידע" to listOf(
            PodcastCategory("educational", "לימודי", "Educational", 0xFF477D95, listOf("חינוך", "לימוד", "educational", "ידע"), listOf("Education", "Educational", "Courses")),
            PodcastCategory("history", "היסטוריה", "History", 0xFF7D4B32, listOf("היסטוריה", "history", "עבר"), listOf("History")),
            PodcastCategory("science", "מדע", "Science", 0xFF006450, listOf("מדע", "science", "חלל", "פיזיקה"), listOf("Science", "Astronomy", "Physics", "Nature")),
            PodcastCategory("documentary", "דוקומנטרי", "Documentary", 0xFF503750, listOf("דוקומנטרי", "documentary", "תעודה", "תחקיר"), listOf("Documentary")),
            PodcastCategory("philosophy", "פילוסופיה", "Philosophy", 0xFF535353, listOf("פילוסופיה", "philosophy", "מחשבה"), listOf("Philosophy", "Society")),
        ),
        "חדשות ועסקים" to listOf(
            PodcastCategory("news", "חדשות ופוליטיקה", "News & Politics", 0xFF1E3264, listOf("חדשות", "אקטואליה", "news", "פוליטיקה"), listOf("News & Politics", "News", "Politics", "Government")),
            PodcastCategory("business", "עסקים", "Business", 0xFF477D95, listOf("עסקים", "יזמות", "business", "כלכלה"), listOf("Business", "Careers")),
            PodcastCategory("economics", "כלכלה", "Economics", 0xFF777777, listOf("כלכלה", "פיננסים", "כסף", "economics"), listOf("Business", "Economics")),
            PodcastCategory("technology", "טכנולוגיה", "Technology", 0xFF1E8555, listOf("טכנולוגיה", "הייטק", "technology", "tech"), listOf("Technology", "Tech")),
            PodcastCategory("sports", "ספורט", "Sports", 0xFFBA5D07, listOf("ספורט", "sports", "כדורגל", "כדורסל"), listOf("Sports", "Football", "Basketball")),
        )
    )

    fun findCategoryById(id: String): PodcastCategory {
        curatedCategories.firstOrNull { it.id == id }?.let { return it }
        for (list in allGroupedCategories.values) {
            list.firstOrNull { it.id == id }?.let { return it }
        }
        return PodcastCategory(
            id = id,
            title = id,
            englishTopic = id,
            colorHex = 0xFF503750,
            discoveryQueries = listOf(id),
            matchingTopics = emptyList(),
        )
    }
}
