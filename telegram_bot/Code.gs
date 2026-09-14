// ==============================================================================
// SpotUI Kosher - Telegram Approval Bot Webhook (Google Apps Script)
// ==============================================================================
// הוראות התקנה תוך דקה:
// 1. היכנס ל- https://script.google.com ולחץ על "New project" ("פרויקט חדש").
// 2. מחק את מה שיש שם, הדבק את כל הקוד הזה ולחץ על סמל השמירה (Ctrl+S).
// 3. לחץ על הכפתור הכחול למעלה "Deploy" -> "New deployment":
//    - בחר סוג (גלגל שיניים): "Web app".
//    - Description: "SpotUI Telegram Approver".
//    - Execute as: "Me" (החשבון שלך).
//    - Who has access: "Anyone" (חשוב מאוד - כדי שטלגרם יוכל לשלוח קריאות).
// 4. לחץ "Deploy", תאשר הרשאות (Authorize access -> Advanced -> Go to project).
// 5. העתק את ה-Web App URL שקיבלת (מתחיל ב- https://script.google.com/macros/s/...).
// 6. הרץ בדפדפן (או שלח לי את ה-URL) את הקישור הבא להפעלת ה-Webhook:
//    https://api.telegram.org/bot8800365444:AAH2W5JBJhrytzmthZMI1TlmzDTpNWnTlo4/setWebhook?url=YOUR_WEB_APP_URL
// ==============================================================================

var CONFIG = {
  TELEGRAM_BOT_TOKEN: "8800365444:AAH2W5JBJhrytzmthZMI1TlmzDTpNWnTlo4",
  GITHUB_TOKEN: "PUT_YOUR_GITHUB_TOKEN_HERE",
  GITHUB_REPO_OWNER: "pmeorsd-afk",
  GITHUB_REPO_NAME: "Spotuikohserr",
  GITHUB_BRANCH: "main"
};

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return ContentService.createTextOutput("No post data");
    }
    
    var update = JSON.parse(e.postData.contents);
    if (update.callback_query) {
      handleCallbackQuery(update.callback_query);
    }
    
    return ContentService.createTextOutput("OK");
  } catch (err) {
    return ContentService.createTextOutput("Error: " + err.toString());
  }
}

function doGet(e) {
  return ContentService.createTextOutput("SpotUI Telegram Approver Bot is running!");
}

function handleCallbackQuery(query) {
  var queryId = query.id;
  var from = query.from;
  var fromId = from.id;
  var fromName = [from.first_name, from.last_name].filter(Boolean).join(" ");
  var msg = query.message;
  if (!msg) return;

  var chatId = msg.chat.id;
  var msgId = msg.message_id;
  var data = query.data || "";
  var text = msg.text || "";

  // 1. בדיקת הרשאת מנהל בערוץ
  var isAdmin = checkIsAdmin(chatId, fromId);
  if (!isAdmin) {
    answerCallbackQuery(queryId, "⛔ רק מנהלי הערוץ מורשים לאשר בקשות!", true);
    return;
  }

  // 2. פענוח הבקשה (אמן או שיר)
  var isArtist = data.indexOf("appr:art") === 0;
  var isTrack = data.indexOf("appr:trk") === 0;

  if (!isArtist && !isTrack) {
    answerCallbackQuery(queryId, "פעולה לא מוכרת", false);
    return;
  }

  // חילוץ מזהה Spotify אם קיים ב-callback_data
  var parts = data.split(":");
  var spotifyId = parts.length >= 3 ? parts[2].trim() : "";

  // חילוץ פרטים מטקסט ההודעה
  var artistName = "";
  var trackTitle = "";

  var artistMatch = text.match(/(?:אמן|Artist):\s*([^\n\r*]+)/i);
  if (artistMatch) artistName = artistMatch[1].trim();

  var trackMatch = text.match(/(?:שיר|Track):\s*([^\n\r*]+)/i);
  if (trackMatch) trackTitle = trackMatch[1].trim();

  var idMatch = text.match(/(?:מזהה ספוטיפיי|ID):\s*`?([a-zA-Z0-9]+)`?/i);
  if (!spotifyId && idMatch) {
    spotifyId = idMatch[1].trim();
  }

  // 3. משיכת whitelist.json הנוכחי מ-GitHub
  var fileInfo = fetchGitHubWhitelist();
  if (!fileInfo || !fileInfo.content) {
    answerCallbackQuery(queryId, "❌ שגיאה במשיכת הנתונים מ-GitHub", true);
    return;
  }

  var whitelist = fileInfo.content;
  var currentSha = fileInfo.sha;
  var itemAddedDescription = "";

  if (isArtist) {
    if (!artistName && !spotifyId) {
      answerCallbackQuery(queryId, "❌ לא זוהה שם אמן בבקשה", true);
      return;
    }

    var exists = whitelist.artists.some(function(a) {
      return (spotifyId && a.id === spotifyId) ||
             (artistName && a.name.toLowerCase() === artistName.toLowerCase());
    });

    if (!exists) {
      whitelist.artists.unshift({
        id: spotifyId,
        name: artistName,
        notes: "approved"
      });
    }
    itemAddedDescription = "האמן " + (artistName || spotifyId);
  } else if (isTrack) {
    if (!trackTitle && !spotifyId) {
      answerCallbackQuery(queryId, "❌ לא זוהה שם שיר בבקשה", true);
      return;
    }

    if (!whitelist.tracks) whitelist.tracks = [];

    var exists = whitelist.tracks.some(function(t) {
      return (spotifyId && t.id === spotifyId) ||
             (trackTitle && t.title.toLowerCase() === trackTitle.toLowerCase());
    });

    if (!exists) {
      whitelist.tracks.unshift({
        id: spotifyId,
        title: trackTitle,
        artist: artistName
      });
    }
    itemAddedDescription = "השיר " + (trackTitle || spotifyId);
  }

  // 4. שמירה ודחיפה חזרה ל-GitHub
  var commitMessage = "Approve " + (artistName || trackTitle || spotifyId) + " via Telegram (@" + (from.username || fromName) + ")";
  var saveSuccess = saveGitHubWhitelist(whitelist, currentSha, commitMessage);

  if (!saveSuccess) {
    answerCallbackQuery(queryId, "❌ שגיאה בשמירה ל-GitHub. נסה שנית.", true);
    return;
  }

  // 5. עדכון ההודעה בטלגרם והסרת כפתור האישור
  var userMention = from.username ? "@" + from.username : fromName;
  var updatedText = text + "\n\n━━━━━━━━━━━━━━━━━━━━\n✅ *אושר ונוסף לרשימה הכשרה ע\"י " + userMention + "!*";

  // משאירים רק את כפתור הספוטיפיי אם קיים
  var newInlineKeyboard = [];
  if (msg.reply_markup && msg.reply_markup.inline_keyboard) {
    for (var r = 0; r < msg.reply_markup.inline_keyboard.length; r++) {
      var row = msg.reply_markup.inline_keyboard[r];
      var newRow = [];
      for (var b = 0; b < row.length; b++) {
        if (row[b].url) {
          newRow.push(row[b]);
        }
      }
      if (newRow.length > 0) newInlineKeyboard.push(newRow);
    }
  }

  editMessageText(chatId, msgId, updatedText, newInlineKeyboard);
  answerCallbackQuery(queryId, "✅ " + itemAddedDescription + " נוסף בהצלחה ל-GitHub!", false);
}

// ------------------------------------------------------------------------------
// עוזרי Telegram API
// ------------------------------------------------------------------------------

function checkIsAdmin(chatId, userId) {
  try {
    var url = "https://api.telegram.org/bot" + CONFIG.TELEGRAM_BOT_TOKEN + "/getChatMember?chat_id=" + chatId + "&user_id=" + userId;
    var resp = UrlFetchApp.fetch(url, { muteHttpExceptions: true });
    var json = JSON.parse(resp.getContentText());
    if (json.ok && json.result) {
      var status = json.result.status;
      return status === "creator" || status === "administrator";
    }
  } catch (e) {
    Logger.log("checkIsAdmin error: " + e);
  }
  return true;
}

function answerCallbackQuery(queryId, text, showAlert) {
  try {
    var url = "https://api.telegram.org/bot" + CONFIG.TELEGRAM_BOT_TOKEN + "/answerCallbackQuery";
    UrlFetchApp.fetch(url, {
      method: "post",
      contentType: "application/json",
      payload: JSON.stringify({
        callback_query_id: queryId,
        text: text,
        show_alert: !!showAlert
      }),
      muteHttpExceptions: true
    });
  } catch (e) {
    Logger.log("answerCallbackQuery error: " + e);
  }
}

function editMessageText(chatId, messageId, newText, inlineKeyboard) {
  try {
    var url = "https://api.telegram.org/bot" + CONFIG.TELEGRAM_BOT_TOKEN + "/editMessageText";
    var payload = {
      chat_id: chatId,
      message_id: messageId,
      text: newText,
      parse_mode: "Markdown"
    };
    if (inlineKeyboard && inlineKeyboard.length > 0) {
      payload.reply_markup = { inline_keyboard: inlineKeyboard };
    }
    UrlFetchApp.fetch(url, {
      method: "post",
      contentType: "application/json",
      payload: JSON.stringify(payload),
      muteHttpExceptions: true
    });
  } catch (e) {
    Logger.log("editMessageText error: " + e);
  }
}

// ------------------------------------------------------------------------------
// עוזרי GitHub API
// ------------------------------------------------------------------------------

function fetchGitHubWhitelist() {
  try {
    var url = "https://api.github.com/repos/" + CONFIG.GITHUB_REPO_OWNER + "/" + CONFIG.GITHUB_REPO_NAME + "/contents/whitelist.json?ref=" + CONFIG.GITHUB_BRANCH;
    var resp = UrlFetchApp.fetch(url, {
      headers: {
        "Authorization": "Bearer " + CONFIG.GITHUB_TOKEN,
        "Accept": "application/vnd.github+json",
        "User-Agent": "SpotUI-Telegram-Bot"
      },
      muteHttpExceptions: true
    });

    if (resp.getResponseCode() >= 200 && resp.getResponseCode() < 300) {
      var data = JSON.parse(resp.getContentText());
      var decodedJson = Utilities.newBlob(Utilities.base64Decode(data.content)).getDataAsString("UTF-8");
      return {
        sha: data.sha,
        content: JSON.parse(decodedJson)
      };
    }
  } catch (e) {
    Logger.log("fetchGitHubWhitelist error: " + e);
  }
  return null;
}

function saveGitHubWhitelist(whitelistObj, currentSha, message) {
  try {
    var url = "https://api.github.com/repos/" + CONFIG.GITHUB_REPO_OWNER + "/" + CONFIG.GITHUB_REPO_NAME + "/contents/whitelist.json";
    var jsonString = JSON.stringify(whitelistObj, null, 2);
    var base64Content = Utilities.base64Encode(Utilities.newBlob(jsonString, "application/json").getBytes());

    var payload = {
      message: message,
      content: base64Content,
      sha: currentSha,
      branch: CONFIG.GITHUB_BRANCH
    };

    var resp = UrlFetchApp.fetch(url, {
      method: "put",
      headers: {
        "Authorization": "Bearer " + CONFIG.GITHUB_TOKEN,
        "Accept": "application/vnd.github+json",
        "User-Agent": "SpotUI-Telegram-Bot"
      },
      contentType: "application/json",
      payload: JSON.stringify(payload),
      muteHttpExceptions: true
    });

    return resp.getResponseCode() >= 200 && resp.getResponseCode() < 300;
  } catch (e) {
    Logger.log("saveGitHubWhitelist error: " + e);
    return false;
  }
}
