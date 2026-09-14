// ==============================================================================
// SpotUI Kosher - Telegram Approval & Report Bot Webhook (Google Apps Script)
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
  return ContentService.createTextOutput("SpotUI Telegram Approver & Report Bot is running!");
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
    answerCallbackQuery(queryId, "⛔ רק מנהלי הערוץ מורשים לאשר או להסיר בקשות!", true);
    return;
  }

  // 2. פענוח הבקשה (אישור או הסרה)
  var isApproveArtist = data.indexOf("appr:art") === 0;
  var isApproveTrack  = data.indexOf("appr:trk") === 0;
  var isRemoveArtist  = data.indexOf("rem:art") === 0;
  var isRemoveTrack   = data.indexOf("rem:trk") === 0;

  if (!isApproveArtist && !isApproveTrack && !isRemoveArtist && !isRemoveTrack) {
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
  var itemActionDescription = "";
  var actionStatusText = "";
  var userMention = from.username ? "@" + from.username : fromName;

  var now = new Date().toISOString();

  function norm(str) {
    return String(str || "").trim().toLowerCase().replace(/\s+/g, " ");
  }

  function matchesArtist(a, spId, name) {
    var cleanSpId = String(spId || "").trim();
    var cleanName = norm(name);
    if (cleanSpId && a.id && String(a.id).trim() === cleanSpId) {
      return true;
    }
    if (cleanName) {
      if (norm(a.canonical_name || a.name) === cleanName) return true;
      var aliases = a.aliases || [];
      for (var i = 0; i < aliases.length; i++) {
        if (norm(aliases[i]) === cleanName) return true;
      }
    }
    return false;
  }

  if (isApproveArtist) {
    if (!artistName && !spotifyId) {
      answerCallbackQuery(queryId, "❌ לא זוהה שם אמן בבקשה", true);
      return;
    }

    if (!whitelist.artists) whitelist.artists = [];
    var found = false;

    for (var i = 0; i < whitelist.artists.length; i++) {
      var a = whitelist.artists[i];
      if (matchesArtist(a, spotifyId, artistName)) {
        if (spotifyId && !a.id) a.id = spotifyId;
        a.status = "approved";
        a.notes = "approved via telegram";
        a.updated_at = now;
        if (!a.aliases) a.aliases = [];
        if (artistName && a.aliases.map(norm).indexOf(norm(artistName)) === -1) {
          a.aliases.push(artistName);
        }
        found = true;
      }
    }

    if (!found) {
      whitelist.artists.unshift({
        id: spotifyId || "",
        canonical_name: artistName || "",
        aliases: artistName ? [artistName] : [],
        status: "approved",
        notes: "approved via telegram",
        updated_at: now
      });
    }

    delete whitelist.blocked_artists;
    itemActionDescription = "האמן " + (artistName || spotifyId) + " אושר בהצלחה";
    actionStatusText = "✅ *אושר ונוסף לרשימה הכשרה ע\"י " + userMention + "!*";

  } else if (isRemoveArtist) {
    if (!artistName && !spotifyId) {
      answerCallbackQuery(queryId, "❌ לא זוהה שם אמן להסרה", true);
      return;
    }

    if (!whitelist.artists) whitelist.artists = [];
    var found = false;

    for (var i = 0; i < whitelist.artists.length; i++) {
      var a = whitelist.artists[i];
      if (matchesArtist(a, spotifyId, artistName)) {
        if (spotifyId && !a.id) a.id = spotifyId;
        a.status = "blocked";
        a.notes = "blocked via telegram";
        a.updated_at = now;
        if (!a.aliases) a.aliases = [];
        if (artistName && a.aliases.map(norm).indexOf(norm(artistName)) === -1) {
          a.aliases.push(artistName);
        }
        found = true;
      }
    }

    if (!found) {
      whitelist.artists.unshift({
        id: spotifyId || "",
        canonical_name: artistName || "",
        aliases: artistName ? [artistName] : [],
        status: "blocked",
        notes: "blocked via telegram",
        updated_at: now
      });
    }

    delete whitelist.blocked_artists;
    itemActionDescription = "האמן " + (artistName || spotifyId) + " הוסר ונחסם";
    actionStatusText = "❌ *הוסר מרשימת ההיתר ונחסם ע\"י " + userMention + "!*";

  } else if (isApproveTrack) {
    if (!trackTitle && !spotifyId) {
      answerCallbackQuery(queryId, "❌ לא זוהה שם שיר בבקשה", true);
      return;
    }

    if (!whitelist.tracks) whitelist.tracks = [];
    var found = false;
    for (var i = 0; i < whitelist.tracks.length; i++) {
      var t = whitelist.tracks[i];
      if ((spotifyId && t.id === spotifyId) || (trackTitle && norm(t.title) === norm(trackTitle))) {
        if (spotifyId && !t.id) t.id = spotifyId;
        t.status = "approved";
        t.updated_at = now;
        found = true;
      }
    }

    if (!found) {
      whitelist.tracks.unshift({
        id: spotifyId || "",
        title: trackTitle || "",
        artist: artistName || "",
        status: "approved",
        updated_at: now
      });
    }

    delete whitelist.blocked_tracks;
    itemActionDescription = "השיר " + (trackTitle || spotifyId) + " נוסף לרשימת ההיתר";
    actionStatusText = "✅ *אושר ונוסף לרשימה הכשרה ע\"י " + userMention + "!*";

  } else if (isRemoveTrack) {
    if (!trackTitle && !spotifyId) {
      answerCallbackQuery(queryId, "❌ לא זוהה שם שיר להסרה", true);
      return;
    }

    if (!whitelist.tracks) whitelist.tracks = [];
    var found = false;
    for (var i = 0; i < whitelist.tracks.length; i++) {
      var t = whitelist.tracks[i];
      if ((spotifyId && t.id === spotifyId) || (trackTitle && norm(t.title) === norm(trackTitle))) {
        if (spotifyId && !t.id) t.id = spotifyId;
        t.status = "blocked";
        t.updated_at = now;
        found = true;
      }
    }

    if (!found) {
      whitelist.tracks.unshift({
        id: spotifyId || "",
        title: trackTitle || "",
        artist: artistName || "",
        status: "blocked",
        updated_at: now
      });
    }

    delete whitelist.blocked_tracks;
    itemActionDescription = "השיר " + (trackTitle || spotifyId) + " נחסם והוסר מההיתר";
    actionStatusText = "❌ *נחסם והוסר מההיתר ע\"י " + userMention + "!*";
  }

  // 4. שמירה ודחיפה חזרה ל-GitHub
  var actionPrefix = (isRemoveArtist || isRemoveTrack) ? "Remove " : "Approve ";
  var commitMessage = actionPrefix + (artistName || trackTitle || spotifyId) + " via Telegram (@" + (from.username || fromName) + ")";
  var saveSuccess = saveGitHubWhitelist(whitelist, currentSha, commitMessage);

  if (!saveSuccess) {
    answerCallbackQuery(queryId, "❌ שגיאה בשמירה ל-GitHub. נסה שנית.", true);
    return;
  }

  // 5. עדכון ההודעה בטלגרם והסרת כפתור הפעולה
  var updatedText = text + "\n\n━━━━━━━━━━━━━━━━━━━━\n" + actionStatusText;

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
  answerCallbackQuery(queryId, "✅ " + itemActionDescription + " בהצלחה ב-GitHub!", false);
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
