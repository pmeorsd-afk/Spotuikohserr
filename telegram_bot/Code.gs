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
    Logger.log("doPost error: " + err);
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

  Logger.log("Callback received: data=" + data + " from=" + fromName + " (" + fromId + ")");

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

  Logger.log("Parsed: spotifyId=" + spotifyId + ", artistName=" + artistName + ", trackTitle=" + trackTitle);

  // 3. משיכת whitelist.json הנוכחי מ-GitHub
  var fileInfo = fetchGitHubWhitelist();
  if (!fileInfo || !fileInfo.content) {
    answerCallbackQuery(queryId, "❌ שגיאה במשיכת הנתונים מ-GitHub", true);
    return;
  }

  var whitelist = fileInfo.content;
  var currentSha = fileInfo.sha;
  var oldJsonString = JSON.stringify(whitelist, null, 2);
  var itemActionDescription = "";
  var actionStatusText = "";
  var userMention = from.username ? "@" + from.username : fromName;
  var now = new Date().toISOString();

  if (isApproveArtist) {
    if (!artistName && !spotifyId) {
      answerCallbackQuery(queryId, "❌ לא זוהה שם אמן בבקשה", true);
      return;
    }
    upsertArtistStatus(whitelist, spotifyId, artistName, "approved", now);
    itemActionDescription = "האמן " + (artistName || spotifyId) + " אושר בהצלחה";
    actionStatusText = "✅ *אושר ונוסף לרשימה הכשרה על ידי " + userMention + "!*";

  } else if (isRemoveArtist) {
    if (!artistName && !spotifyId) {
      answerCallbackQuery(queryId, "❌ לא זוהה שם אמן להסרה", true);
      return;
    }
    upsertArtistStatus(whitelist, spotifyId, artistName, "blocked", now);
    itemActionDescription = "האמן " + (artistName || spotifyId) + " הוסר ונחסם";
    actionStatusText = "❌ *הוסר מרשימת ההיתר ונחסם על ידי " + userMention + "!*";

  } else if (isApproveTrack) {
    if (!trackTitle && !spotifyId) {
      answerCallbackQuery(queryId, "❌ לא זוהה שם שיר בבקשה", true);
      return;
    }
    upsertTrackStatus(whitelist, spotifyId, trackTitle, artistName, "approved", now);
    itemActionDescription = "השיר " + (trackTitle || spotifyId) + " נוסף לרשימת ההיתר";
    actionStatusText = "✅ *אושר ונוסף לרשימה הכשרה על ידי " + userMention + "!*";

  } else if (isRemoveTrack) {
    if (!trackTitle && !spotifyId) {
      answerCallbackQuery(queryId, "❌ לא זוהה שם שיר להסרה", true);
      return;
    }
    upsertTrackStatus(whitelist, spotifyId, trackTitle, artistName, "blocked", now);
    itemActionDescription = "השיר " + (trackTitle || spotifyId) + " נחסם והוסר מההיתר";
    actionStatusText = "❌ *נחסם והוסר מההיתר על ידי " + userMention + "!*";
  }

  // עדכון גרסה ותאריך קנוני
  whitelist.schema_version = 2;
  whitelist.version = Number(whitelist.version || 0) + 1;
  whitelist.last_updated = now;

  var newJsonString = JSON.stringify(whitelist, null, 2);
  if (oldJsonString === newJsonString) {
    Logger.log("WARNING: Old JSON and New JSON are identical! Save is NO-OP.");
  } else {
    Logger.log("JSON changed: new version=" + whitelist.version);
  }

  // 4. שמירה ודחיפה חזרה ל-GitHub
  var actionPrefix = (isRemoveArtist || isRemoveTrack) ? "Remove " : "Approve ";
  var commitMessage = actionPrefix + (artistName || trackTitle || spotifyId) + " via Telegram (@" + (from.username || fromName) + ")";
  var saveSuccess = saveGitHubWhitelist(whitelist, currentSha, commitMessage);

  if (!saveSuccess) {
    answerCallbackQuery(queryId, "❌ שגיאה בשמירה ל-GitHub. נסה שנית.", true);
    return;
  }

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
// ניהול רשומות קנוני (Upsert & Uniqueness)
// ------------------------------------------------------------------------------

function norm(str) {
  return String(str || "").trim().toLowerCase().replace(/\s+/g, " ");
}

function uniqueNames(names) {
  var result = [];
  var seen = {};
  names.forEach(function(name) {
    var clean = String(name || "").trim();
    if (!clean) return;
    var key = clean.toLowerCase();
    if (!seen[key]) {
      seen[key] = true;
      result.push(clean);
    }
  });
  return result;
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

function upsertArtistStatus(whitelist, spotifyId, artistName, status, now) {
  if (!whitelist.artists) whitelist.artists = [];

  var matches = [];
  for (var i = 0; i < whitelist.artists.length; i++) {
    if (matchesArtist(whitelist.artists[i], spotifyId, artistName)) {
      matches.push(whitelist.artists[i]);
    }
  }

  var target;
  if (matches.length > 0) {
    target = matches[0];
  } else {
    target = {
      id: spotifyId || "",
      canonical_name: artistName || "",
      aliases: [],
      status: status
    };
    whitelist.artists.unshift(target);
  }

  var allAliases = [];
  matches.forEach(function(a) {
    if (a.canonical_name) allAliases.push(a.canonical_name);
    if (a.name) allAliases.push(a.name);
    (a.aliases || []).forEach(function(alias) {
      allAliases.push(alias);
    });
  });
  if (artistName) allAliases.push(artistName);

  target.id = spotifyId || target.id || "";
  target.canonical_name = target.canonical_name || artistName || "";
  target.aliases = uniqueNames(allAliases);
  target.status = status;
  target.notes = status === "approved" ? "approved via telegram" : "blocked via telegram";
  target.updated_at = now;

  // ביטול כפילויות – משאירים רק את הרשומה הקנונית target
  whitelist.artists = whitelist.artists.filter(function(a) {
    return a === target || matches.indexOf(a) === -1;
  });

  delete whitelist.blocked_artists;
  Logger.log("upsertArtistStatus completed: id=" + target.id + ", name=" + target.canonical_name + ", status=" + target.status);
  return target;
}

function upsertTrackStatus(whitelist, spotifyId, trackTitle, artistName, status, now) {
  if (!whitelist.tracks) whitelist.tracks = [];

  var cleanSpId = String(spotifyId || "").trim();
  var normTitle = norm(trackTitle);

  var matches = [];
  for (var i = 0; i < whitelist.tracks.length; i++) {
    var t = whitelist.tracks[i];
    var match = (cleanSpId && t.id && String(t.id).trim() === cleanSpId) ||
                (normTitle && t.title && norm(t.title) === normTitle);
    if (match) matches.push(t);
  }

  var target;
  if (matches.length > 0) {
    target = matches[0];
  } else {
    target = {
      id: cleanSpId,
      title: trackTitle || "",
      artist: artistName || "",
      status: status
    };
    whitelist.tracks.unshift(target);
  }

  target.id = cleanSpId || target.id || "";
  target.title = target.title || trackTitle || "";
  target.artist = target.artist || artistName || "";
  target.status = status;
  target.updated_at = now;

  // ביטול כפילויות
  whitelist.tracks = whitelist.tracks.filter(function(t) {
    return t === target || matches.indexOf(t) === -1;
  });

  delete whitelist.blocked_tracks;
  Logger.log("upsertTrackStatus completed: id=" + target.id + ", title=" + target.title + ", status=" + target.status);
  return target;
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

function getEffectiveGithubToken() {
  try {
    var token = PropertiesService.getScriptProperties().getProperty("GITHUB_TOKEN");
    if (token) return token;
  } catch (e) {}
  return CONFIG.GITHUB_TOKEN;
}

function fetchGitHubWhitelist() {
  try {
    var token = getEffectiveGithubToken();
    var url = "https://api.github.com/repos/" + CONFIG.GITHUB_REPO_OWNER + "/" + CONFIG.GITHUB_REPO_NAME + "/contents/whitelist.json?ref=" + CONFIG.GITHUB_BRANCH;
    var resp = UrlFetchApp.fetch(url, {
      headers: {
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "User-Agent": "SpotUI-Telegram-Bot"
      },
      muteHttpExceptions: true
    });

    var code = resp.getResponseCode();
    if (code >= 200 && code < 300) {
      var data = JSON.parse(resp.getContentText());
      var decodedJson = Utilities.newBlob(Utilities.base64Decode(data.content)).getDataAsString("UTF-8");
      return {
        sha: data.sha,
        content: JSON.parse(decodedJson)
      };
    } else {
      Logger.log("fetchGitHubWhitelist error code: " + code + " => " + resp.getContentText());
    }
  } catch (e) {
    Logger.log("fetchGitHubWhitelist error: " + e);
  }
  return null;
}

function saveGitHubWhitelist(whitelistObj, currentSha, message) {
  try {
    var token = getEffectiveGithubToken();
    var url = "https://api.github.com/repos/" + CONFIG.GITHUB_REPO_OWNER + "/" + CONFIG.GITHUB_REPO_NAME + "/contents/whitelist.json";
    var jsonString = JSON.stringify(whitelistObj, null, 2);
    Logger.log("saveGitHubWhitelist: writing " + jsonString.length + " bytes, sha=" + currentSha);

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
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "User-Agent": "SpotUI-Telegram-Bot"
      },
      contentType: "application/json",
      payload: JSON.stringify(payload),
      muteHttpExceptions: true
    });

    var code = resp.getResponseCode();
    Logger.log("saveGitHubWhitelist result: HTTP " + code + " => " + resp.getContentText());
    return code >= 200 && code < 300;
  } catch (e) {
    Logger.log("saveGitHubWhitelist error: " + e);
    return false;
  }
}
