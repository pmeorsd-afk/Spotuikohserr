# Podcast Home — Audit וניתוח Reference

תאריך Audit: 2026-10-13. מצב נוכחי: **גרסה ראשונה של הפיד והבחירה המרובה מומשה ב־2026-10-15; אימות במכשיר עדיין נדרש.**

עדכון 2026-10-15: המשתמש אישר במפורש לממש את הפיד ואת הבחירה המרובה. גרסה ראשונה של שניהם מומשה, כולל השלמות הכותרת/Back/גלילה. התנהגות, בדיקות ומגבלות האימות מתועדות ב־PODCAST_HOME_IMPLEMENTATION.md וב־CURRENT_WORK.md. גבולות האישור והפערים המתוארים בהמשך הם המצב ההיסטורי בזמן ה־Audit.

עדכון 2026-10-14: בעקבות בקשה מפורשת חדשה הוסרה חסימת ״בקרוב״ ב־Home, ונחשפה תצוגת Podcasts/Following הקיימת. מידות כפתורי הכותרת עודכנו לפי צילומי המסך החדשים. זהו תת־שלב של גישה ועיצוב הכפתורים בלבד; יתר התוכנית להלן, כולל הפיד הדינמי, הבחירה המרובה והאנימציה המלאה, עדיין לא הושלם. פרטי השינוי והבדיקות ב־CURRENT_WORK.md וב־HOME_UI_ALIGNMENT.md. תיאורי החסימה בהמשך מתעדים את מצב הקוד בזמן ה־Audit.

המסמך מסכם בדיקת קוד וניתוח הסרטון לפני שינוי קוד האפליקציה. הוראת העבודה היא: “אל תשנה קוד עדיין בשלב ה-Audit”, ובהמשך לעצור אחרי כל שלב מימוש.

מקורות:
- [הבריף המלא](<C:/Users/Orel lugasi/.codex/attachments/1a9a7469-95a9-4614-9982-d295ac7ed3cf/Pasted text.txt>).
- [סרטון המקור](<C:/Users/Orel lugasi/Documents/MuMuSharedFolder/VideoRecords/Spotify(1).mp4>).
- AGENTS.md והמסמכים PROJECT_CONTEXT, ARCHITECTURE, PODCAST_ARCHITECTURE, MEDIASESSION_WAZE, UI_UX_CONSTRAINTS, TELEMETRY, STATUS ו־CURRENT_WORK.

## 1. מה נמדד בסרטון

הסרטון בגודל 900×1600 פיקסלים, כ־29.97 פריימים לשנייה ובאורך כ־63.19 שניות. נבדקו תמונות לאורך כל הסרטון ו־75 פריימים רצופים סביב בחירת Podcasts. המדידות הן בפיקסלים של ההקלטה: סטייה של פיקסלים בודדים וכ־33 מילישניות בתזמון אפשרית. חותמות הזמן של חילוץ נקודתי הן מקורבות. צפיפות Android אינה מקודדת בסרטון ולכן אין להסיק ממנו ערכי dp בוודאות.

### תנועת Podcasts

בסביבות 3.5 שניות:
- “הכול” מאבד את הבחירה ו־Podcasts הופך לירוק כמעט מיד.
- הכפתור הירוק נשאר באותו מיקום ובאותו גודל: בקירוב x=449…593, y=48…96, כלומר 144×48 פיקסלים.
- “במעקב” יוצא **שמאלה מאחורי** Podcasts, במסך RTL. הוא מתגלה בהדרגה עד שהקצה השמאלי מגיע לכ־x=346.
- הצורה הירוקה נשארת גלולה מעוגלת. אזור “במעקב” הכהה נמצא מאחוריה; אין להפוך את Podcasts לחצי מלבן.
- “מוזיקה”, “הכול” והאווטאר נשארים במקומם. לא נראים שינוי קנה מידה, תנועה אנכית או שינוי של כל המכולה.
- התנועה העיקרית אורכת בקירוב 180–220ms. מהחלפת הבחירה ועד התייצבות הכפתור עוברות בערך 300ms, כולל השהיה קצרה. אין עדות חזותית משכנעת לקפיצה או overshoot.
- אי אפשר להפריד בוודאות בין fade לבין חשיפה באמצעות clipping מתוך ההקלטה. גם עקומת ה־easing המדויקת אינה ידועה.
- התוכן מגיע מאוחר יותר, בסביבות 4.2–4.5 שניות. זהו זמן טעינה נפרד מתנועת הכפתור; אין להעתיק הפסקה ריקה כחלק מהאנימציה.
- בבחירת “במעקב” גם האזור שלו נעשה ירוק, בעוד Podcasts נשאר ירוק.

הצעת מימוש, ולא עובדה שניתן לקרוא מהסרטון: Compose native עם גלולת Podcasts קבועה, שכבה אחורית נחתכת ונעה אופקית, tween של כ־220ms וכיול מול ההקלטה. להתחיל ללא scale או spring; לבדוק אם נדרשת השהיה קצרה לפני החשיפה. הטעינה לא תעכב את האנימציה.

ראיות: [רצף תנועה מקוצר](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/build/reports/podcast-home-audit/podcasts-motion.png>), [פריימים רצופים — חלק 3](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/build/reports/podcast-home-audit/button-3.png>), [מדידות סריקה](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/build/reports/podcast-home-audit/motion-measurements.json>).

### מכולת כרטיס הפרק

בפריים 5.8 שניות:
- המכולה הגדולה הראשונה: x=24…876, y=326…686 — **852×360 פיקסלים**, יחס **2.367:1**.
- כרטיס “פרקים שאולי תאהבו” בהמשך: x=24…876, y≈860…1220 — אותו גודל.
- שוליים אופקיים: 24 פיקסלים מכל צד, כ־2.67% מרוחב ההקלטה; רוחב הכרטיס כ־94.67% מהמסך.
- הווידאו שבתוך המקור הוא רק 640×360, ביחס 16:9, עם פסים שחורים בצדדים בתוך המכולה הרחבה.
- **ב־SpotUI עטיפת הפרק תמלא את המכולה החיצונית 852×360 באמצעות ContentScale.Crop.** אין לעוות את העטיפה, ואין לצמצם את הכרטיס ל־16:9 או לכיסוי ריבועי קטן.
- בכרטיס ההמלצות לפרקים נראים קצוות מעוגלים ברדיוס מוערך 16–18 פיקסלים. כרטיס הווידאו הראשון שונה מעט בפינותיו; כרטיס הפרקים הוא נקודת הייחוס למימוש.
- אזור המטא־דאטה נמצא מתחת לתמונה, במרווח כ־18 פיקסלים: תמונת תוכנית קטנה מימין כ־60×60, כותרת ושורת תוכנית/תאריך, ותפריט בצד שמאל.
- אחרי המטא־דאטה יש מרווח של כ־54 פיקסלים עד תחילת כותרת הסקשן הבא; בין הכותרת לתמונה הבאה כ־14–18 פיקסלים.
- ה־header דביק, התוכן גולל, וה־MiniPlayer והניווט התחתון נשארים מעליו.

ב־SpotUI לא יתווספו וידאו, mute של preview, autoplay, WebView או סקשן “סרטונים”. תמונת הפרק והפעולות יתייחסו לאותה זהות Episode. ערכי dp יכוילו לפי צפיפות ה־viewport שבו נבדוק, תוך שמירת יחס המכולה במסכים שונים.

ראיות: [פריים מלא 5.8 שניות](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/build/reports/podcast-home-audit/frame-05.80.png>), [פריים מלא 8.5 שניות](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/build/reports/podcast-home-audit/frame-08.50.png>).

### זרימת “במעקב”

מה שנראה בפועל:
1. סביב 19–20 שניות נבחר “במעקב”. מופיעים “הפרקים האחרונים” ומצב ריק עם “עדיין אין לכם פרקים חדשים”.
2. לחיצה על “עיון בפודקאסטים” פותחת מסך מלא “בחירת פודקאסטים”.
3. המסך כולל חיפוש, כרטיסי Shows וכרטיסי מעבר לקטגוריות, בחירה מרובה וסימון ✓ על כל בחירה.
4. “סיום” בתחתית מוביל למצב שמירה/טעינה ולאחריו לפרקים של התוכניות שנבחרו.
5. ב־Following מופיעים פרקים ותאריכים; זה אינו רק גריד של התוכניות במעקב.

מידות בחירה שנצפו:
- שדה החיפוש: כ־852×72 פיקסלים, x=24…876, y=180…252.
- חמש עמודות בהקלטה הזאת; עטיפות כ־146×146, מרווחים כ־24–25 פיקסלים ופינות כ־20 פיקסלים.
- תוויות מתחת לעטיפה, עד כשתי שורות; סימון בחירה עגול לבן כ־48×48 בפינה השמאלית העליונה.
- “סיום”: כ־145×73 פיקסלים, ממורכז בתחתית.
- במסך הבחירה לא מוצגים ה־MiniPlayer והניווט התחתון.

התאמה מוצעת: מספר העמודות יחושב מהרוחב הזמין ומגודל הכרטיס; חמש עמודות אינן קבוע לכל מכשיר. תישמר גלילה טבעית ומיקום נגיש ל“סיום” במסכים נמוכים ובפתיחת מקלדת.

ההקלטה אינה מראה ביטול/Back או תוצאות של חיפוש מוקלד. הצעה למימוש: טיוטת בחירה שמתחילה מהמעקבים הקיימים; “סיום” שומר את השינויים ו־Back מבטל את הטיוטה. זו החלטה מוצעת ולא התנהגות שאומתה בסרטון. זמני ההמתנה המקוריים לא יועתקו; נציג רק עבודה אמיתית.

“יוצרי פודקאסטים” ימומשו כאן כתוכניות עם **Show ID מדויק**, שם, עטיפה ו־publisher. אין להוסיף אותם למעקב אחר אמני מוזיקה או להשתמש בשם publisher כתחליף לזהות Show.

בסוף הסרטון מופיע גם “סינון ומיון” עם לא נוגן, נוגן חלקית, וידאו, הורדות, תאריך פרסום ושם תוכנית. זהו ממצא נוסף; אין צורך להכניס את כל המסננים לשלב הראשון. מסנן וידאו אינו מתאים להיקף האודיו בלבד. מסנני התקדמות יתבססו רק על מצב אמין קיים.

ראיות: [Following ריק](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/build/reports/podcast-home-audit/frame-19.90.png>), [מסך הבחירה](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/build/reports/podcast-home-audit/frame-24.00.png>), [בחירות מסומנות](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/build/reports/podcast-home-audit/frame-34.00.png>), [Following עם פרקים](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/build/reports/podcast-home-audit/frame-42.00.png>), [סינון ומיון](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/build/reports/podcast-home-audit/frame-48.00.png>).

## 2. מצב המימוש הקיים

| תחום | קובץ / מחלקה | ממצא |
|---|---|---|
| לחיצה על Podcasts | [HomeScreen](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/screens/HomeScreen.kt:195>) | שני callbacks, בשורות 195 ו־214, עוצרים את הבחירה ופותחים coming-soon במקום setFilter(PODCASTS). |
| ההודעה הנוכחית | [PodcastsComingSoonDialog](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/components/PodcastsComingSoonDialog.kt:80>) | Dialog של Compose עם “מסך פודקאסט חדשני בקרוב”; אינו Toast או Snackbar. יש גם badge ב־header. |
| מצב המסננים | [HomeViewModel](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/viewmodel/HomeViewModel.kt:25>) | ALL / MUSIC / PODCASTS / FOLLOWING כבר קיימים. אין שמירת previous-filter או טיפול Back ייעודי. |
| אנימציה קיימת | [PodcastFilterGroup](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/screens/HomeScreen.kt:522>) | יש fade/expand/slide, אך כרגע אינה נגישה דרך הלחיצה הרגילה. הצורה והחפיפה דורשות התאמה לסרטון. |
| הצגת Home | [HomeFeedContent](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/screens/HomeScreen.kt:329>) | header דביק, אך נעלם בענפי loading/error של Music Home; אין scroll state נפרד לכל מסנן. |
| Podcasts/Following קיימים | [PodcastModeContent](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/screens/HomeScreen.kt:734>) / [FollowedShowsListContent](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/screens/HomeScreen.kt:811>) | תוכן סטטי/רשימות Shows. הכותרת “הפרקים האחרונים” אינה נתמכת כרגע באיסוף Episodes. |
| מצב ריק ו־+ | [FollowingEmptyState](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/screens/HomeScreen.kt:901>) | בסיס UI קיים; כפתורי ההוספה עוברים ל־Hub הכללי, ללא בחירה מרובה או “סיום”. |
| מסך Hub | [PodcastHubScreen](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/screens/PodcastHubScreen.kt:53>) | פורטל קטגוריות בשתי עמודות. אינו משתמש בפועל ב־PodcastHubViewModel הקיים. |
| טעינת Shows | [PodcastHubViewModel](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/viewmodel/PodcastHubViewModel.kt:16>) / [Api](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/data/api/Api.kt:608>) | discovery אמיתי, cache בזיכרון ובדיסק לשעה, שלוש שאילתות קיימות ו־Semaphore(2). |
| קטגוריות | [PodcastCategoryScreen](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/screens/PodcastCategoryScreen.kt:70>) / [PodcastAllCategoriesScreen](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/screens/PodcastAllCategoriesScreen.kt:41>) | קטגוריות/topics, cache, pagination ותוצאות הדרגתיות קיימים. |
| חיפוש | [Api.searchEverything](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/data/api/Api.kt:330>) | חיפוש קיים כולל podcast results. מסך הבחירה ישתמש ב־Shows בלבד ובזהות מדויקת, עם debounce וביטול תוצאה ישנה. |
| פרקי RSS | [AppRepository](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/repository/AppRepository.kt:46>) / [Api.getPodcastEpisodes](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/data/api/Api.kt:383>) | גילוי feed, RSS/parser ומיפוי פרקים קיימים. אין צורך ב־resolver או repository מקביל. |
| מודל Episode | [PodcastEpisodeUiModel](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/data/entity/PodcastEpisodeUiModel.kt:8>) | זהות show/episode, עטיפה, תיאור, תאריך, משך ו־toSongModel typed. |
| מסך תוכנית | [ShowScreen](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/screens/ShowScreen.kt:125>) / [ShowViewModel](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/viewmodel/ShowViewModel.kt:22>) | מסך תוכנית ופרקים עם מעקב ופעולות קיימות. |
| כרטיס Episode | [EpisodeCardItem](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/components/EpisodeCardItem.kt:53>) | פעולות ועטיפה בטוחה קיימות, אבל המכולה הנוכחית ריבועית 56dp ואינה מתאימה ל־Home המבוקש. |
| שמירת מעקב | [PodcastFollowPref](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/data/preferences/PodcastFollowPref.kt:14>) | Show ID, שם, עטיפה, זמן מעקב; dedup, follow/unfollow ו־revision תגובתי. |
| ניווט | [Routes](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/navigation/Routes.kt:36>) / [MyNavHost](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/navigation/MyNavHost.kt:103>) | Hub/category/show ופתיחת Episode מדויק קיימים. אין מסך בחירת מעקב ייעודי. |

אין כרגע Podcast recommender, איסוף פרקים חדשים מכל התוכניות במעקב, cache מתמשך של סקשני Podcast Home או שיתוף בקשות catalog בין הסקשנים. lastSeenEpisodeGuid נשמר ב־Follow snapshot אך אינו מממש איסוף/סימון פרקים חדשים.

## 3. מקורות להמלצות שכבר זמינים

| Signal | מקור | מגבלה |
|---|---|---|
| תוכניות במעקב | [PodcastFollowPref](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/data/preferences/PodcastFollowPref.kt:14>) | זהות מדויקת וזמן מעקב זמינים. |
| תוכניות ופרקים שהושמעו | [LocalListeningTracker](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/data/local/LocalListeningTracker.kt:194>) | יש Show ID, play count וזמן אחרון. completedCount מציין חציית 75%, לא בהכרח סיום מלא. |
| היסטוריית האזנה | [ListeningHistoryPref](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/data/preferences/ListeningHistoryPref.kt:22>) | עד 500 רשומות typed עם timestamps ו־snapshot. |
| המשך וסיום אמיתי | [PlaybackStatePref](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/data/preferences/PlaybackStatePref.kt:63>) / [PodcastProgressStore](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/playback/PodcastProgressStore.kt:23>) | להשתמש ב־bookmark וב־completed אמיתי; רק כאשר זהות הפרק מתחברת בדיוק. |
| תוצאות שנפתחו מחיפוש | [RecentItemsPref](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/data/preferences/RecentItemsPref.kt:12>) | עד 20 ישויות שנבחרו; אין timestamps ואין היסטוריית טקסט השאילתות. |
| topics/categories | [Api](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/data/api/Api.kt:704>) | קיימים. Hub disk cache משמיט topics ותיאור; Category cache שומר topics. |
| תאריך פרסום | PodcastEpisodeUiModel ו־PodcastDateFormatter | להשתמש בפענוח ובמיון הקיימים; תאריך חסר אינו תאריך “חדש”. |

אין תיעוד כללי של כניסות ל־ShowScreen; רק בחירות שמגיעות מחיפוש זמינות כיום כ־signal חלקי. אין store לחשיפות קודמות של המלצות.

מזהה RSS פנימי ומזהה Spotify אינם תמיד ניתנים לחיבור מתוך המידע השמור. לא מנחשים התאמת completion או התקדמות לפי שם פרק. אם אין קשר מדויק, ה־signal אינו זמין.

## 4. הצעת הרכבת הנתונים

היעד לגרסה הראשונה המלאה: שני סקשנים אמיתיים — “פרקים שאולי תאהבו” ו“חדש מפודקאסטים במעקב”; למשתמש חדש הסקשן השני יכול להיות “פרקים חדשים” מאותו מקור discovery. “המשך להאזין” יהיה סקשן שלישי רק כשיש נתוני Episode והתקדמות אמינים. אין להציג כותרת ריקה או לשכפל פרקים כדי למלא סקשן.

שכבת הרכבה קטנה מעל AppRepository:
- snapshot מקומי של **מטא־דאטה וזהויות** לסקשנים, הצגה מיידית מה־cache ורענון רק בכניסה מבוקרת/TTL/פעולת משתמש.
- שמירת snapshot בין פתיחות; לא לשמור URL זמני כתחליף לפתרון מקור ניגון. מסלול הניגון ממשיך להשתמש בתשתית הקיימת.
- לכל היותר ארבעה Shows בסבב הראשוני, עד שתי קריאות catalog במקביל, ועד 6–8 פרקים חדשים לכל Show. שיתוף תוצאות ובקשות פעילות בין הסקשנים.
- אלה גבולות למספר בקשות Shows ולמספר candidates שנשמרים; ה־API הקיים עדיין קורא ומפענח feed שלם.
- בעקבות רשימת מעקב ארוכה: קבוצות טעינה נוספות מבוקרות. אין להציג ארבע תוכניות כאילו הן כל המעקב; יופיע מצב טעינה/המשך מתאים.
- אין refresh בכל recomposition. שינוי מעקב מבטל רק את החלק הרלוונטי; נתונים שכבר מוצגים נשארים בזמן רענון או כשל חלקי.
- score גבוה למעקב ולהאזנה קודמת; בינוני ל־topic ולטריות; ענישה חזקה לסיום אמיתי; ענישה קלה לחשיפה אחרונה רק לאחר הוספת store מקומי מוגבל.
- בחירה משוקללת מתוך עד 20–30 candidates, הגבלת ריבוי מאותו Show ו־seed יציב לסשן. הרוטציה לא תחליף כרטיסים באמצע גלילה ולא תצריך רשת נוספת.
- תיקון צר של שמירת topics ב־PodcastHubCachePref ישייך לשלב הנתונים. אין מערכת המלצות שרתית חדשה.

מוזיקה/InnerTube/YouTube/TrackVideoCache, MediaSession/Waze, telemetry, buffering ו־EpisodePlaybackResolver אינם חלק מהמימוש.

הערת שילוב להמשך: [ShowViewModel.playEpisode](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/ui/viewmodel/ShowViewModel.kt:74>) ו־HomeViewModel.playTrack מפרסמים metadata לפני פתרון המקור; אין להעתיק אותם למסך החדש. יש להשתמש בנתיב הניגון המוגן הקיים, כולל commit לאחר הכנה, תוך שימור הזהות וה־DirectAudio. [SongPlayer.resumeResolvedPlayback](<C:/Users/Orel lugasi/.gemini/antigravity/scratch/Spotuikohserr/app/src/main/java/com/music/spotui/di/SongPlayer.kt:438>) הוא entry point קיים לבדיקה ממוקדת בעת החיבור. לא הוכח bug הכרחי ב־EpisodePlaybackResolver ולא מוצע לשנותו.

## 5. שלבי מימוש מוצעים

כל שלב מסתיים בדוח קבצים ובדיקות ועצירה לאישור. אישור שלב 1 אינו אישור לכל השלבים.

| שלב | תוצאה | גבולות ובדיקות |
|---|---|---|
| **1 — כפתור ומצב Home** | הסרת Dialog/badge וחסימות הלחיצה; אנימציה לפי הסרטון; PODCASTS/FOLLOWING נגישים; Back טבעי ל־Home הקודם וגלילה נפרדת למסננים. | ה־header נגיש גם בזמן טעינת/כשל פיד המוזיקה, בתיקון צר. בלי chooser, דירוג או RSS aggregation. בדיקת מעברים, Back ולחיצות מהירות; debug compilation ללא APK. תוכן fallback קיים הוא זמני, ואינו מסך דינמי מוגמר. |
| **2 — מעטפת וכרטיס ראשון אמיתי** | מכולת cover ביחס שנמדד, metadata/פעולות מדויקות, גלילה, loading/error/empty וסקשן פרקים אמיתי ראשון דרך ה־repository הקיים. | הצגה מיידית של cache, רענון מוגבל, זהות פרק ו־direct audio בלבד. בדיקות אי־שכפול בקשות וכשל חלקי; השוואת viewport רגיל ונמוך. |
| **2ב — בחירת תוכניות למעקב** | Following ריק → “עיון בפודקאסטים” → חיפוש/קטגוריות/בחירה מרובה → “סיום”; סנכרון עם ShowScreen ו־Home דרך מאגר המעקב הקיים. | שלב נפרד לאישור. שמירת Show IDs בלבד, שימור מעקבים קיימים, החלטה מפורשת על Done/Back, חיפוש עם תוצאות מאוחרות מוגנות. |
| **3 — “פרקים שאולי תאהבו”** | candidates, ranking, freshness, diversity ורוטציה מקומית, כולל משתמש ללא היסטוריה. | cache משותף, concurrency=2, מגבלות מועמדים, session יציב; בדיקות דירוג, סיום אמיתי, שונות ו־dedup. |
| **4 — סקשן דינמי נוסף** | חדש מהתוכניות במעקב / פרקים חדשים, ו־Following שמציג Episodes בפועל. | מיון תאריכים, יותר מקבוצת Shows אחת, חלקיות/כשל, שינוי מעקב. Continue רק עם זהות ומצב אמינים. |
| **5 — כיול ואימות** | כיוונון תנועה, spacing, פינות, skeleton ו־viewport קצר/רגיל. | בדיקה ויזואלית מול הווידאו, RTL, מקלדת/גלילה; רגרסיית מוזיקה, פודקאסט direct, Player/MiniPlayer/Search/ShowScreen. מסננים נוספים רק בהיקף שיאושר. |

בשלב מימוש יש להריץ בדיקות רלוונטיות וקומפילציית debug בהתאם למגבלת “ללא APK” הקיימת. בדיקות JVM וקומפילציה אינן הוכחה להתאמה ויזואלית או לניגון במכשיר. התקנה/בדיקות חיות של הבנייה החדשה ממתינות לבנייה שתתבקש בנפרד.

## 6. מצב האימות והקבצים במשימה זו

- נבדקו קוד מקומי, הוראות הפרויקט והסרטון; לא שונה קוד אפליקציה, resolver או preference store.
- לא הורצו בדיקות, Gradle build, יצירת APK או התקנה במסגרת ה־Audit.
- נוצרו הדוח הזה, עדכון סטטוס ב־CURRENT_WORK, וסקריפטים/פריימים תחת app/build/reports/podcast-home-audit, תיקייה שמתעלמים ממנה ב־Git.
- לצורך חילוץ מקומי הותקן imageio-ffmpeg 0.6.0 בתיקיית הדוח בלבד. לא שונו תלויות Gradle או תלויות runtime של האפליקציה.
- תוצאות הבדיקות הקודמות של Android Auto שייכות למשימה הקודמת; אינן אימות של Podcast Home שטרם מומש.
- סביבת העבודה מכילה שינויים קודמים רבים. הם נשמרו ולא אופסו.

**נקודת ההמשך: אישור שלב 1 בלבד, ולא התחלת כל התוכנית.**

