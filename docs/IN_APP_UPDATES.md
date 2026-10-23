# עדכונים מתוך האפליקציה

מומש בקוד ב־23.10.2026. טרם נבנה או הופץ APK עם התכונה, ולא שונה הקובץ החי ב־GitHub.

## מה המשתמש רואה

האפליקציה ממשיכה לבדוק את `version.json` הקיים. כשיש גרסה חדשה וקישור APK ישיר,
הכפתור **עדכן עכשיו** מוריד את הקובץ בתוך האפליקציה. המסך כהה, עם פס התקדמות
ירוק, אחוזים וגודל ההורדה. יש ביטול, ניסיון חוזר והמשך ברקע בעדכון שאינו חובה.
ללא גודל קובץ ידוע מוצגת התקדמות מונפשת ללא אחוזים מומצאים.

לאחר ההורדה נבדקים הקובץ, מספר הגרסה, שם חבילת האפליקציה והחתימה.
לאחר מכן נפתח חלון ההתקנה של Android לאישור המשתמש. אם חסרה הרשאת
התקנה מהאפליקציה, נפתח מסך ההרשאה וחוזרים להתקנה לאחר האישור.
הורדה קיימת נשמרת גם אם תהליך האפליקציה נסגר; ההתקנה נפתחת רק כשהאפליקציה בחזית.

## איך מפרסמים עדכון

1. מכינים APK חדש עם `versionCode` גבוה מהגרסה הקודמת, `versionName` חדש,
   אותו מזהה חבילה ואותה חתימה. שינוי המספר ב־JSON בלבד לא משנה את ה־APK.
2. מעלים את ה־APK כקובץ מצורף ל־[GitHub Release](https://github.com/pmeorsd-afk/Spotuikohserr/releases).
   הקובץ צריך להיות ציבורי וזמין להורדה ללא התחברות. מפרסמים את ה־Release לפני שינוי ה־JSON.
3. מעתיקים את הקישור הישיר של קובץ ה־APK. לדוגמה, אם ה־tag הוא `v2.9`
   ושם הקובץ הוא `SpotUI-2.9.apk`, הקישור יהיה:
   `https://github.com/pmeorsd-afk/Spotuikohserr/releases/download/v2.9/SpotUI-2.9.apk`.
   זו דוגמת כתובת בלבד; הקובץ הזה לא הועלה במסגרת העבודה.
4. עורכים את אותו [version.json](https://github.com/pmeorsd-afk/Spotuikohserr/blob/main/version.json):
   מעדכנים את `versionCode` ואת `versionName` כך שיתאימו ל־APK,
   ואת `apkUrl` לקישור הישיר. אפשר לעדכן גם את הכותרת וההודעה.
5. משאירים את `downloadUrl` כקישור לדף ההורדה/Release עבור גרסאות ישנות.
   בודקים את העדכון במכשיר לפני שמגדירים `forceUpdate: true`.

דוגמה בלבד:

```json
{
  "versionCode": 29,
  "versionName": "2.9",
  "title": "עדכון חדש מחכה לכם",
  "message": "שיפורים ותיקונים באפליקציה",
  "downloadUrl": "https://github.com/pmeorsd-afk/Spotuikohserr/releases/tag/v2.9",
  "apkUrl": "https://github.com/pmeorsd-afk/Spotuikohserr/releases/download/v2.9/SpotUI-2.9.apk",
  "apkSha256": "",
  "forceUpdate": false,
  "updateButtonText": "עדכן עכשיו",
  "cancelButtonText": "מאוחר יותר"
}
```

`apkSha256` הוא שדה אופציונלי לבדיקה נוספת של הקובץ. אפשר להשאירו ריק.
אם ממלאים אותו, משתמשים ב־64 התווים שמחזירה הפקודה על ה־APK הסופי:

```powershell
(Get-FileHash -LiteralPath 'C:\path\SpotUI-2.9.apk' -Algorithm SHA256).Hash
```

לכל גרסה כדאי לתת tag ושם קובץ משלה. אין להחליף את תוכן ה־APK שכבר פורסם
תחת אותו מספר גרסה וקישור, כי ייתכן שמשתמש כבר הוריד אותו.

**הגרסה הראשונה שמכילה את המנגנון הזה תותקן בדרך הישנה.** החל מהגרסה הזו,
העדכונים הבאים יוכלו לרדת מתוך האפליקציה. `apkUrl` ריק משאיר את פתיחת קישור
ההורדה הישן. בקובץ המקומי נוספו רק השדות הריקים; הגרסה נשארה 2.8 / 28.

## גרסת מנהל

User ו־Admin הן חבילות נפרדות. אם מפיצים גם עדכון למנהל, מוסיפים
`adminApkUrl` ו־`adminApkSha256` עם קובץ ה־Admin המתאים. בלי קישור מנהל,
גרסת Admin תפתח את `downloadUrl`; היא לא תוריד אוטומטית את קובץ ה־User.
שתי הגרסאות משתמשות באותם `versionCode` ו־`versionName` במסמך.

## מימוש וגבולות הבדיקה

- `UpdateManifest` שומר תאימות למסמך הקיים ולמעטפת Base64 של GitHub API.
- בדיקת העדכון משתמשת באימות TLS ושם שרת רגילים, עם תעודות המערכת והמשתמש
  שכבר מוגדרות ב־`network_security_config`. הוסרה עקיפת אימות התעודות הישנה
  רק מלקוח העדכונים; לקוחות הניגון לא שונו. יש לבדוק גם ברשת המסוננת שבשימוש.
- `UpdateDownloadSession` מתאם הורדה אחת, ביטול, ניסיון חוזר, שחזור ואימות.
- `AndroidUpdateDownloads` משתמש ב־DownloadManager של Android, בקבצים בתיקיית
  האפליקציה וב־FileProvider מוגבל לתיקיית העדכונים בלבד. קבצי ההורדה ומזהה
  ההורדה אינם עוברים בגיבוי/העברת מכשיר.
- אימות נוסף נעשה ממש לפני מסירת הקובץ למתקין, גם אחרי החזרה ממסך ההרשאה.
  נדחים קובץ שאינו APK, חבילה שונה, חתימה שונה, גרסה לא תואמת/לא חדשה,
  Android ישן מדי ו־SHA-256 שגוי אם סופק.
- המסך מוגבל לרוחב 420dp, מתאים מרווחים לרוחב זמין וניתן לגלילה במסכים
  נמוכים או עם גופן מוגדל. אין בדיקות של דגם מכשיר ואין שינוי בנגני המוזיקה/פודקאסטים.
- אין התקנה שקטה, שינוי חתימה, פרסום Release או הקפצת גרסה אוטומטית.

תוצאות הבדיקות מופיעות ב־`CURRENT_WORK.md` וב־`app/build/reports/in-app-update/`.
הבדיקות המקומיות אינן בדיקת DownloadManager או מתקין Android אמיתיים.
לא היה מכשיר מחובר. לפני הפצה נדרשת בדיקה של APK ישן מול חדש על מסך קטן
ומסך רגיל: הורדה ואחוזים, ניתוק וחזרת רשת, רקע/פתיחה מחדש, ביטול וניסיון חוזר,
הרשאה שנדחתה/אושרה, התקנה וסיום ההודעה בגרסה החדשה. יש לבדוק גם שהמוזיקה
ופודקאסט RSS ממשיכים להתנהג כרגיל בזמן ההורדה ולפני אישור ההתקנה.

מקורות: [GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases),
[Android DownloadManager](https://developer.android.com/reference/android/app/DownloadManager),
[הרשאת התקנה](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()),
[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider).
