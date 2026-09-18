// ==============================================================================
// SpotUI Kosher - Telegram Approval & Report Bot Webhook + Admin Command API
// (Google Apps Script - Unified Backend Engine)
// ==============================================================================

var CONFIG = {
  TELEGRAM_BOT_TOKEN: "8800365444:AAH2W5JBJhrytzmthZMI1TlmzDTpNWnTlo4",
  TELEGRAM_CHANNEL_ID: "-1004491387106", // @spotifty_kosher
  GITHUB_TOKEN: "YOUR_GITHUB_TOKEN",
  GITHUB_REPO_OWNER: "pmeorsd-afk",
  GITHUB_REPO_NAME: "Spotuikohserr",
  GITHUB_BRANCH: "main",
  ADMIN_API_KEY: "SPOTUI_ADMIN_SECRET_KEY_2026"
};

// ------------------------------------------------------------------------------
// HTTP POST Endpoint (Telegram Webhook + SpotUI Admin Command API)
// ------------------------------------------------------------------------------

function doPost(e) {
  try {
    if (!e || !e.postData) {
      if (e && e.parameter && e.parameter.action) {
        return handleAdminApiRequest(e, {});
      }
      return ContentService.createTextOutput(JSON.stringify({ ok: false, error: "no_post_data" }))
        .setMimeType(ContentService.MimeType.JSON);
    }

    var rawContents = "";
    try {
      rawContents = (e.postData.getDataAsString && e.postData.getDataAsString("UTF-8")) || e.postData.contents || "";
    } catch (convErr) {
      rawContents = e.postData.contents || "";
    }

    var body = {};
    try {
      body = JSON.parse(rawContents);
    } catch (parseErr) {
      Logger.log("doPost JSON parse error: " + parseErr);
      return ContentService.createTextOutput(JSON.stringify({ ok: false, error: "invalid_json" }))
        .setMimeType(ContentService.MimeType.JSON);
    }

    // 1. Telegram Callback Query Webhook
    if (body.callback_query) {
      handleCallbackQuery(body.callback_query);
      return ContentService.createTextOutput(JSON.stringify({ ok: true }))
        .setMimeType(ContentService.MimeType.JSON);
    }

    // 2. SpotUI Admin Command API
    if (body.type === "admin_action" || body.action || body.source === "admin_app") {
      return handleAdminApiRequest(e, body);
    }

    return ContentService.createTextOutput(JSON.stringify({ ok: false, error: "unknown_payload_type" }))
      .setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    Logger.log("doPost error: " + err);
    return ContentService.createTextOutput(JSON.stringify({ ok: false, error: String(err) }))
      .setMimeType(ContentService.MimeType.JSON);
  }
}

// ------------------------------------------------------------------------------
// HTTP GET Endpoint (Public Read API for Android 0-Second Sync + Admin Action)
// ------------------------------------------------------------------------------

function doGet(e) {
  try {
    // If admin action is called via GET query parameters
    if (e && e.parameter && e.parameter.action) {
      return handleAdminApiRequest(e, {});
    }

    var cache = CacheService.getScriptCache();
    var json = cache ? cache.get("whitelist_v2") : null;

    if (!json) {
      json = PropertiesService.getScriptProperties().getProperty("whitelist_v2");
    }

    if (!json) {
      var fileInfo = fetchGitHubWhitelist();
      if (fileInfo && fileInfo.content) {
        json = JSON.stringify(fileInfo.content, null, 2);
        saveWhitelistCache(json);
      }
    }

    if (!json) {
      return ContentService.createTextOutput(JSON.stringify({
        ok: false,
        error: "whitelist_unavailable"
      })).setMimeType(ContentService.MimeType.JSON);
    }

    return ContentService.createTextOutput(json).setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    Logger.log("doGet error: " + err);
    return ContentService.createTextOutput(JSON.stringify({
      ok: false,
      error: String(err)
    })).setMimeType(ContentService.MimeType.JSON);
  }
}

// ------------------------------------------------------------------------------
// Cache Helpers (Multi-tier: CacheService + PropertiesService)
// ------------------------------------------------------------------------------

function saveWhitelistCache(jsonString) {
  try {
    CacheService.getScriptCache().put("whitelist_v2", jsonString, 21600); // 6 hours
  } catch (e) {
    Logger.log("saveWhitelistCache CacheService error: " + e);
  }
}

function saveCachedWhitelistInfo(whitelistObj, sha) {
  try {
    var cache = CacheService.getScriptCache();
    var json = typeof whitelistObj === "string" ? whitelistObj : JSON.stringify(whitelistObj, null, 2);
    if (json.length < 100000) {
      cache.put("gh_wl_content", json, 21600); // 6 hours
      cache.put("whitelist_v2", json, 21600);
    }
    if (sha) {
      cache.put("gh_wl_sha", String(sha), 21600);
    }
  } catch (e) {
    Logger.log("saveCachedWhitelistInfo error: " + e);
  }
}

function getEffectiveWhitelistInfo() {
  try {
    var cache = CacheService.getScriptCache();
    var cachedJson = cache.get("gh_wl_content");
    var cachedSha = cache.get("gh_wl_sha");
    if (cachedJson && cachedSha) {
      return {
        sha: cachedSha,
        content: JSON.parse(cachedJson)
      };
    }
  } catch (e) {
    Logger.log("getEffectiveWhitelistInfo cache read error: " + e);
  }
  var fresh = fetchGitHubWhitelist();
  if (fresh && fresh.content) {
    saveCachedWhitelistInfo(fresh.content, fresh.sha);
  }
  return fresh;
}

// ------------------------------------------------------------------------------
// Admin API Handler & Authentication
// ------------------------------------------------------------------------------

function getEffectiveAdminKey() {
  try {
    var key = PropertiesService.getScriptProperties().getProperty("ADMIN_API_KEY");
    if (key) return key;
  } catch (e) {}
  return CONFIG.ADMIN_API_KEY || "SPOTUI_ADMIN_SECRET_KEY_2026";
}

function handleAdminApiRequest(e, body) {
  var authHeader = (e && e.headers && (e.headers["Authorization"] || e.headers["authorization"])) || "";
  var token = "";
  if (authHeader.indexOf("Bearer ") === 0) {
    token = authHeader.substring(7).trim();
  } else if (body.admin_token) {
    token = String(body.admin_token).trim();
  } else if (e && e.parameter && e.parameter.token) {
    token = String(e.parameter.token).trim();
  }

  var expectedKey = getEffectiveAdminKey();
  if (token !== expectedKey) {
    Logger.log("Unauthorized admin request. Token was: " + token);
    return ContentService.createTextOutput(JSON.stringify({
      ok: false,
      error: "unauthorized"
    })).setMimeType(ContentService.MimeType.JSON);
  }

  var action = String(body.action || (e && e.parameter && e.parameter.action) || "").trim();
  var artist = body.artist || {};
  var track = body.track || {};
  var spotifyId = String(artist.spotify_id || track.spotify_id || body.id || (e && e.parameter && e.parameter.id) || "").trim();
  var artistName = String(artist.name || track.artist || body.name || (e && e.parameter && e.parameter.name) || "").trim();
  var trackTitle = String(track.title || body.title || (e && e.parameter && e.parameter.title) || "").trim();
  var requestId = String(body.request_id || (e && e.parameter && e.parameter.request_id) || "").trim();
  var actorName = (body.actor && body.actor.id) ? String(body.actor.id) : "SpotUI-Admin";

  // Normalize action names
  if (action === "appr:art") action = "approve_artist";
  if (action === "rem:art")  action = "block_artist";
  if (action === "appr:trk") action = "approve_track";
  if (action === "rem:trk")  action = "block_track";

  if (!action) {
    return ContentService.createTextOutput(JSON.stringify({
      ok: false,
      error: "missing_action"
    })).setMimeType(ContentService.MimeType.JSON);
  }

  var result = applyWhitelistAction({
    source: "admin",
    action: action,
    requestId: requestId,
    spotifyId: spotifyId,
    artistName: artistName,
    trackTitle: trackTitle,
    actorName: actorName
  });

  return ContentService.createTextOutput(JSON.stringify(result))
    .setMimeType(ContentService.MimeType.JSON);
}

// ------------------------------------------------------------------------------
// Central Unified Whitelist Action Engine (ScriptLock + Idempotency + Single Truth)
// ------------------------------------------------------------------------------

function checkIdempotency(requestId) {
  if (!requestId) return null;
  try {
    var str = CacheService.getScriptCache().get("req:" + requestId);
    if (!str) {
      str = PropertiesService.getScriptProperties().getProperty("req:" + requestId);
    }
    if (str) return JSON.parse(str);
  } catch (e) {
    Logger.log("checkIdempotency error: " + e);
  }
  return null;
}

function recordIdempotency(requestId, result) {
  if (!requestId) return;
  try {
    var str = JSON.stringify(result);
    CacheService.getScriptCache().put("req:" + requestId, str, 7200); // 2 hours
    PropertiesService.getScriptProperties().setProperty("req:" + requestId, str);
  } catch (e) {
    Logger.log("recordIdempotency error: " + e);
  }
}

function saveWithConflictRetry(whitelistObj, currentSha, commitMessage, updateFn) {
  var sha = currentSha;
  var wl = whitelistObj;
  for (var attempt = 1; attempt <= 3; attempt++) {
    var saveRes = saveGitHubWhitelist(wl, sha, commitMessage);
    if (saveRes && saveRes.ok) {
      saveCachedWhitelistInfo(wl, saveRes.newSha || sha);
      return saveRes;
    }
    if (saveRes && saveRes.statusCode === 409) {
      Logger.log("GitHub 409 conflict on attempt " + attempt + " — re-fetching fresh file from GitHub...");
      Utilities.sleep(300 * attempt); // Jitter to let GitHub branch ref settle
      var fresh = fetchGitHubWhitelist();
      if (fresh && fresh.content) {
        wl = fresh.content;
        sha = fresh.sha;
        updateFn(wl); // Re-apply modifications to latest file content!
      } else {
        break;
      }
    } else {
      break;
    }
  }
  return saveRes;
}

function applyWhitelistAction(params) {
  // 1. Idempotency fast-path
  if (params.requestId) {
    var cached = checkIdempotency(params.requestId);
    if (cached) {
      Logger.log("Idempotent hit for request_id: " + params.requestId);
      return cached;
    }
  }

  try {
    // Fetch current GitHub file and SHA (fast cache path, fallback to GitHub GET)
    var fileInfo = getEffectiveWhitelistInfo();
    if (!fileInfo || !fileInfo.content) {
      return { ok: false, error: "github_fetch_failed" };
    }

    var isApprove = (params.action === "approve_artist" || params.action === "approve_track");
    var status = isApprove ? "approved" : "blocked";
    var actorDesc = params.actorName || (params.source === "admin" ? "SpotUI Admin" : "Telegram Admin");
    var defaultNotes = (isApprove ? "approved" : "blocked") + " via " + (params.source === "admin" ? "SpotUI Admin" : "telegram");
    var notes = params.notes || defaultNotes;
    var commitSubject = params.artistName || params.trackTitle || params.spotifyId || "item";
    var commitMessage = (isApprove ? "Approve " : "Remove ") + commitSubject + " via " + actorDesc;

    function applyUpdate(wl) {
      var now = new Date().toISOString();
      if (params.action === "approve_artist" || params.action === "block_artist") {
        upsertArtistStatus(wl, params.spotifyId, params.artistName, status, now, notes);
      } else {
        upsertTrackStatus(wl, params.spotifyId, params.trackTitle, params.artistName, status, now, notes);
      }
      wl.schema_version = 2;
      wl.version = Number(wl.version || 0) + 1;
      wl.last_updated = now;
    }

    var whitelist = fileInfo.content;
    var currentSha = fileInfo.sha;
    applyUpdate(whitelist);

    var saveResult = saveWithConflictRetry(whitelist, currentSha, commitMessage, applyUpdate);
    if (!saveResult || !saveResult.ok) {
      return { ok: false, error: "github_save_failed" };
    }

    var result = {
      ok: true,
      version: whitelist.version,
      status: status,
      action: params.action,
      id: params.spotifyId,
      name: params.artistName,
      title: params.trackTitle,
      request_id: params.requestId || ""
    };

    if (params.requestId) {
      recordIdempotency(params.requestId, result);
    }

    // Notify Telegram admin channel if action was from admin app
    if (params.source === "admin") {
      try {
        notifyTelegramChannel(params, whitelist);
      } catch (e) {
        Logger.log("notifyTelegramChannel error: " + e);
      }
    }

    return result;
  } catch (err) {
    Logger.log("applyWhitelistAction error: " + err);
    return { ok: false, error: String(err) };
  }
}

// ------------------------------------------------------------------------------
// Telegram Callback Query Handler & Concurrency Engine
// ------------------------------------------------------------------------------

function safeAnswerCallbackQuery(queryId, text, showAlert) {
  try {
    answerCallbackQuery(queryId, text, showAlert);
  } catch (err) {
    Logger.log("safeAnswerCallbackQuery failed: " + err);
  }
}

function makeProcessingKey(chatId, messageId, action, target) {
  return [
    "wl-proc",
    String(chatId),
    String(messageId),
    String(action || ""),
    String(target || "")
  ].join(":");
}

function tryClaimCallback(key, ttlSeconds) {
  try {
    var cache = CacheService.getScriptCache();
    if (cache.get(key)) {
      return false; // Already in progress
    }
    cache.put(key, "1", ttlSeconds || 60);
    return true;
  } catch (e) {
    Logger.log("tryClaimCallback error: " + e);
    return true;
  }
}

function releaseClaim(key) {
  try {
    CacheService.getScriptCache().remove(key);
  } catch (e) {}
}

function extractUrlButtons(inlineKeyboard) {
  var urlButtons = [];
  if (inlineKeyboard && inlineKeyboard.length > 0) {
    for (var r = 0; r < inlineKeyboard.length; r++) {
      var row = inlineKeyboard[r];
      var newRow = [];
      for (var b = 0; b < row.length; b++) {
        if (row[b].url) {
          newRow.push(row[b]);
        }
      }
      if (newRow.length > 0) urlButtons.push(newRow);
    }
  }
  return urlButtons;
}

function buildProcessingKeyboard(originalKeyboard, actionText) {
  var keyboard = [];
  keyboard.push([{ text: actionText || "⏳ מעבד פעולה ב-GitHub...", callback_data: "noop" }]);
  var urlButtons = extractUrlButtons(originalKeyboard);
  for (var i = 0; i < urlButtons.length; i++) {
    keyboard.push(urlButtons[i]);
  }
  return keyboard;
}

function checkIsAdminCached(chatId, userId) {
  try {
    var cache = CacheService.getScriptCache();
    var key = "admins:" + String(chatId);
    var cached = cache.get(key);
    var adminIds = null;
    if (cached) {
      try { adminIds = JSON.parse(cached); } catch (e) {}
    }
    if (!adminIds || !Array.isArray(adminIds)) {
      adminIds = fetchTelegramAdminIds(chatId);
      if (adminIds && adminIds.length > 0) {
        cache.put(key, JSON.stringify(adminIds), 300); // 5 minutes
      }
    }
    if (adminIds && adminIds.length > 0) {
      return adminIds.indexOf(String(userId)) !== -1;
    }
  } catch (e) {
    Logger.log("checkIsAdminCached error: " + e);
  }
  return checkIsAdmin(chatId, userId);
}

function fetchTelegramAdminIds(chatId) {
  try {
    var url = "https://api.telegram.org/bot" + CONFIG.TELEGRAM_BOT_TOKEN + "/getChatAdministrators?chat_id=" + chatId;
    var resp = UrlFetchApp.fetch(url, { muteHttpExceptions: true });
    var json = JSON.parse(resp.getContentText());
    if (json.ok && Array.isArray(json.result)) {
      return json.result.map(function(member) {
        return String(member.user.id);
      });
    }
  } catch (e) {
    Logger.log("fetchTelegramAdminIds error: " + e);
  }
  return null;
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

  // If user clicked the temporary in-progress button
  if (data === "noop") {
    safeAnswerCallbackQuery(queryId, "⏳ הפעולה כבר מתבצעת כעת...", false);
    return;
  }

  var isApproveArtist = data.indexOf("appr:art") === 0;
  var isApproveTrack  = data.indexOf("appr:trk") === 0;
  var isRemoveArtist  = data.indexOf("rem:art") === 0;
  var isRemoveTrack   = data.indexOf("rem:trk") === 0;

  if (!isApproveArtist && !isApproveTrack && !isRemoveArtist && !isRemoveTrack) {
    safeAnswerCallbackQuery(queryId, "פעולה לא מוכרת", false);
    return;
  }

  var isApprove = (isApproveArtist || isApproveTrack);

  // 1. EARLY ACK IMMEDIATELY (<100ms)! Dismisses Telegram's loading spinner instantly!
  var earlyAckText = isApprove ? "⏳ הבקשה נקלטה, מאשר ב-GitHub..." : "⏳ הבקשה נקלטה, מסיר ב-GitHub...";
  safeAnswerCallbackQuery(queryId, earlyAckText, false);

  var parts = data.split(":");
  var spotifyId = parts.length >= 3 ? parts[2].trim() : "";

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

  var action = "";
  if (isApproveArtist) action = "approve_artist";
  else if (isRemoveArtist) action = "block_artist";
  else if (isApproveTrack) action = "approve_track";
  else if (isRemoveTrack) action = "block_track";

  var targetIdentifier = spotifyId || artistName || trackTitle || "item";

  // 2. DEDUPLICATION (using CacheService, no locking bottleneck)
  var processingKey = makeProcessingKey(chatId, msgId, action, targetIdentifier);
  var claimed = tryClaimCallback(processingKey, 60);
  if (!claimed) {
    Logger.log("Callback already claimed/processing: " + processingKey);
    return;
  }

  var originalKeyboard = (msg.reply_markup && msg.reply_markup.inline_keyboard) || [];

  // 3. CHECK ADMIN (CACHED)
  var isAdmin = checkIsAdminCached(chatId, fromId);
  if (!isAdmin) {
    releaseClaim(processingKey);
    try {
      editMessageText(chatId, msgId, text + "\n\n⛔ *רק מנהלי הערוץ מורשים לאשר או להסיר בקשות!*", originalKeyboard);
    } catch (e) {}
    safeAnswerCallbackQuery(queryId, "⛔ רק מנהלי הערוץ מורשים לבצע פעולה זו!", true);
    return;
  }

  var userMention = from.username ? "@" + from.username : fromName;

  // 4. INSTANT TELEGRAM UI CONFIRMATION (<250ms!)
  // User gets instant green checkmark and button is removed immediately, preventing any waiting delay
  var actionStatusText = isApprove
    ? ("✅ *אושר ונוסף לרשימה הכשרה על ידי " + userMention + "!*")
    : ("❌ *הוסר מרשימת ההיתר ונחסם על ידי " + userMention + "!*");

  var updatedText = text + "\n\n━━━━━━━━━━━━━━━━━━━━\n" + actionStatusText;
  var finalInlineKeyboard = extractUrlButtons(originalKeyboard);

  try {
    editMessageReplyMarkup(chatId, msgId, finalInlineKeyboard);
    editMessageText(chatId, msgId, updatedText, finalInlineKeyboard);
  } catch (uiErr) {
    Logger.log("Instant UI update error: " + uiErr);
  }

  // 5. APPLY WHITELIST ACTION TO GITHUB (queued safely with ScriptLock in background)
  var result = applyWhitelistAction({
    source: "telegram",
    action: action,
    spotifyId: spotifyId,
    artistName: artistName,
    trackTitle: trackTitle,
    actorName: userMention,
    requestId: queryId
  });

  if (!result || !result.ok) {
    releaseClaim(processingKey);
    var errDesc = (result ? result.error : "unknown");
    try {
      editMessageText(chatId, msgId, text + "\n\n❌ *שגיאה בעדכון ה-Whitelist ב-GitHub (" + errDesc + ")*", originalKeyboard);
    } catch (e) {}
    return;
  }
}

// ------------------------------------------------------------------------------
// ניהול רשומות קנוני (Upsert & Deduplication)
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

function upsertArtistStatus(whitelist, spotifyId, artistName, status, now, notes) {
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
  target.notes = notes || (status === "approved" ? "approved via telegram" : "blocked via telegram");
  target.updated_at = now;

  whitelist.artists = whitelist.artists.filter(function(a) {
    return a === target || matches.indexOf(a) === -1;
  });

  delete whitelist.blocked_artists;
  Logger.log("upsertArtistStatus: id=" + target.id + ", name=" + target.canonical_name + ", status=" + target.status);
  return target;
}

function upsertTrackStatus(whitelist, spotifyId, trackTitle, artistName, status, now, notes) {
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
  target.notes = notes || (status === "approved" ? "approved" : "blocked");
  target.updated_at = now;

  whitelist.tracks = whitelist.tracks.filter(function(t) {
    return t === target || matches.indexOf(t) === -1;
  });

  delete whitelist.blocked_tracks;
  Logger.log("upsertTrackStatus: id=" + target.id + ", title=" + target.title + ", status=" + target.status);
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

function editMessageReplyMarkup(chatId, messageId, inlineKeyboard) {
  try {
    var url = "https://api.telegram.org/bot" + CONFIG.TELEGRAM_BOT_TOKEN + "/editMessageReplyMarkup";
    var payload = {
      chat_id: chatId,
      message_id: messageId,
      reply_markup: { inline_keyboard: inlineKeyboard || [] }
    };
    UrlFetchApp.fetch(url, {
      method: "post",
      contentType: "application/json",
      payload: JSON.stringify(payload),
      muteHttpExceptions: true
    });
  } catch (e) {
    Logger.log("editMessageReplyMarkup error: " + e);
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
    var resp = UrlFetchApp.fetch(url, {
      method: "post",
      contentType: "application/json",
      payload: JSON.stringify(payload),
      muteHttpExceptions: true
    });

    var code = resp.getResponseCode();
    if (code >= 400) {
      Logger.log("editMessageText failed HTTP " + code + " with Markdown, retrying without parse_mode: " + resp.getContentText());
      delete payload.parse_mode;
      UrlFetchApp.fetch(url, {
        method: "post",
        contentType: "application/json",
        payload: JSON.stringify(payload),
        muteHttpExceptions: true
      });
    }
  } catch (e) {
    Logger.log("editMessageText error: " + e);
  }
}

function sendTelegramMessage(chatId, text) {
  try {
    var url = "https://api.telegram.org/bot" + CONFIG.TELEGRAM_BOT_TOKEN + "/sendMessage";
    var payload = {
      chat_id: chatId,
      text: text,
      parse_mode: "Markdown"
    };
    UrlFetchApp.fetch(url, {
      method: "post",
      contentType: "application/json",
      payload: JSON.stringify(payload),
      muteHttpExceptions: true
    });
  } catch (e) {
    Logger.log("sendTelegramMessage error: " + e);
  }
}

function notifyTelegramChannel(params, whitelist) {
  var actionDesc = "";
  if (params.action === "approve_artist") actionDesc = "אישור אמן להיתר תמונות ✅";
  else if (params.action === "block_artist") actionDesc = "חסימת אמן מההיתר ⛔";
  else if (params.action === "approve_track") actionDesc = "אישור שיר להיתר תמונות ✅";
  else if (params.action === "block_track") actionDesc = "חסימת שיר מההיתר ⛔";

  var entityDesc = params.artistName || params.trackTitle || params.spotifyId;
  var text = "🛡️ *עדכון רשימת היתר מתוך SpotUI Admin*\n\n" +
             "👤 *ישות:* " + entityDesc + "\n" +
             (params.spotifyId ? ("🆔 *Spotify ID:* `" + params.spotifyId + "`\n") : "") +
             "🔄 *פעולה:* " + actionDesc + "\n" +
             "📦 *גרסה חדשה:* `" + whitelist.version + "`\n" +
             "📱 *מקור:* " + (params.actorName || "SpotUI-Admin");

  sendTelegramMessage(CONFIG.TELEGRAM_CHANNEL_ID, text);
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
        "User-Agent": "SpotUI-Backend"
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

    var base64Content = Utilities.base64Encode(jsonString, Utilities.Charset.UTF_8);

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
        "User-Agent": "SpotUI-Backend"
      },
      contentType: "application/json",
      payload: JSON.stringify(payload),
      muteHttpExceptions: true
    });

    var code = resp.getResponseCode();
    var respText = resp.getContentText();
    Logger.log("saveGitHubWhitelist result: HTTP " + code + " => " + respText);
    var newSha = null;
    if (code >= 200 && code < 300) {
      try {
        var respJson = JSON.parse(respText);
        if (respJson && respJson.content && respJson.content.sha) {
          newSha = respJson.content.sha;
        }
      } catch (e) {}
      return { ok: true, statusCode: code, newSha: newSha };
    }
    return { ok: false, statusCode: code, error: respText };
  } catch (e) {
    Logger.log("saveGitHubWhitelist error: " + e);
    return { ok: false, statusCode: 500, error: String(e) };
  }
}
