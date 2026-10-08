// ==============================================================================
// SpotUI Kosher - Cloudflare Worker (Production v3.1)
// Real-time Telegram Webhook + GitHub Whitelist API
//
// Architecture:
//
// Telegram
//    │
//    ▼
// Cloudflare Worker
//    ├── fast callback ACK (<20ms)
//    ├── GitHub PUT (write with 409-retry)
//    └── GitHub Contents API GET (read)
//             │
//             ▼
//       10s per-isolate memory cache
//             │
//             ▼
//       Android SpotUI Kosher
//
// Features & Fixes:
// - Completely removed raw.githubusercontent.com (eliminates Fastly 5-minute CDN delay)
// - GitHub Contents API is the single source of truth for both reads and writes
// - Fast 10-second memory cache per isolate with observability headers (X-Cache-Source, X-Whitelist-Version)
// - Cache is updated only AFTER successful GitHub PUT
// - Stale memory fallback if GitHub API experiences network/rate limits
// ==============================================================================

const CONFIG = {
  TELEGRAM_BOT_TOKEN: "YOUR_TELEGRAM_BOT_TOKEN",
  TELEGRAM_CHANNEL_ID: "-1004491387106", // @spotifty_kosher
  GITHUB_TOKEN: "YOUR_GITHUB_TOKEN",
  GITHUB_REPO_OWNER: "pmeorsd-afk",
  GITHUB_REPO_NAME: "Spotuikohserr",
  GITHUB_BRANCH: "main",
  GAS_SYNC_URL: "https://script.google.com/macros/s/AKfycbxjKBX2VHdyKfkih9EOgTOs5C08iFKqOEOaSeis1Ov1NZPBjR2HEVtMX-aAEricAXpPJw/exec",
  ADMIN_API_KEY: "SPOTUI_ADMIN_SECRET_KEY_2026"
};

// ------------------------------------------------------------------------------
// In-Memory Cache (Per-Isolate)
// ------------------------------------------------------------------------------
const WHITELIST_CACHE_TTL_MS = 10 * 1000; // 10 seconds
let memoryWhitelist = null;
let memoryWhitelistFetchedAt = 0;
// ------------------------------------------------------------------------------
// In-Memory Telemetry State
// ------------------------------------------------------------------------------
const telemetrySessions = new Map(); // userId -> { lastSeen, isPlaying, track, totalSeconds, appVersion }
const telemetryStats = {
  totalUsersSeen: new Set(),
  topTracks: new Map(),       // "title - artist" -> { title, artist, count }
  topArtists: new Map()       // artist -> count
};

// ==============================================================================
// Worker Entry Point
// ==============================================================================
export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // --------------------------------------------------------------------------
    // 0. CORS Preflight
    // --------------------------------------------------------------------------
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type, User-Agent, X-Requested-With",
          "Access-Control-Max-Age": "86400"
        }
      });
    }

    // --------------------------------------------------------------------------
    // 1. לוח מחוונים וסטטיסטיקות שידור חי (Live Analytics Dashboard)
    // --------------------------------------------------------------------------
    if (request.method === "GET" && (url.pathname === "/dashboard" || url.pathname === "/live")) {
      return handleGetDashboard();
    }

    if (request.method === "GET" && (url.pathname === "/api/analytics" || url.pathname === "/analytics")) {
      return handleGetAnalytics();
    }

    // --------------------------------------------------------------------------
    // 2. קבלת טלמטריה מהאפליקציה (Live Telemetry POST)
    // --------------------------------------------------------------------------
    if (request.method === "POST" && (url.pathname === "/api/telemetry" || url.pathname === "/telemetry")) {
      return handleTelemetryPost(request, env, ctx);
    }

    // --------------------------------------------------------------------------
    // 3. הגדרת Webhook בטלגרם בלחיצה ישירה מהדפדפן
    // --------------------------------------------------------------------------
    if (url.pathname === "/setWebhook") {
      const workerUrl = `${url.origin}/`;
      const allowedUpdatesParam = encodeURIComponent(JSON.stringify(["message", "channel_post", "callback_query"]));
      const tgRes = await fetch(
        `https://api.telegram.org/bot${CONFIG.TELEGRAM_BOT_TOKEN}/setWebhook?url=${encodeURIComponent(workerUrl)}&allowed_updates=${allowedUpdatesParam}&drop_pending_updates=true`
      );
      const tgData = await tgRes.json();
      return jsonResponse({ workerUrl, telegramResponse: tgData });
    }

    // --------------------------------------------------------------------------
    // 4. שרת Whitelist מהיר מבוסס GitHub Contents API
    // --------------------------------------------------------------------------
    if (
      request.method === "GET" &&
      (url.pathname === "/whitelist.json" || url.pathname === "/whitelist" || url.pathname === "/exec")
    ) {
      return handleGetWhitelist(request);
    }

    // --------------------------------------------------------------------------
    // 5. בדיקת תקינות (Health Check)
    // --------------------------------------------------------------------------
    if (request.method === "GET") {
      const ver = memoryWhitelist ? memoryWhitelist.version : "not_cached_yet";
      return new Response(
        `SpotUI Telegram Bot Worker v3.1 is running 🚀\nWhitelist API: /whitelist.json\nCurrent Memory Version: ${ver}\nLive Dashboard: /dashboard\nAnalytics API: /api/analytics`,
        {
          status: 200,
          headers: { "Content-Type": "text/plain; charset=utf-8" }
        }
      );
    }

    // --------------------------------------------------------------------------
    // 6. Telegram Webhook (POST)
    // --------------------------------------------------------------------------
    if (request.method !== "POST") {
      return new Response("Method not allowed", { status: 405 });
    }

    try {
      const update = await request.json();

      if (update.callback_query) {
        // עונים לטלגרם ב-20ms בלבד, וממשיכים את העדכון ברקע דרך waitUntil
        ctx.waitUntil(handleTelegramCallback(update.callback_query));
        return new Response("OK", { status: 200 });
      }

      return new Response("OK", { status: 200 });
    } catch (err) {
      console.error("Worker error:", err);
      return new Response("Error: " + String(err?.message || err), { status: 500 });
    }
  }
};

// ==============================================================================
// GET WHITELIST (GitHub Contents API + 10s In-Memory Cache)
// ==============================================================================
async function handleGetWhitelist(request) {
  const requestId = crypto.randomUUID();
  const now = Date.now();

  // --------------------------------------------------------------------------
  // בדיקת מטמון מקומי ב-Isolate (חוסך פניות מיותרות ל-GitHub)
  // --------------------------------------------------------------------------
  if (
    memoryWhitelist &&
    memoryWhitelistFetchedAt > 0 &&
    now - memoryWhitelistFetchedAt < WHITELIST_CACHE_TTL_MS
  ) {
    return whitelistResponse(memoryWhitelist, {
      cacheSource: "memory-fresh",
      requestId
    });
  }

  // --------------------------------------------------------------------------
  // משיכה ישירה מ-GitHub Contents API (ללא מטמון 5 דקות של Fastly CDN)
  // --------------------------------------------------------------------------
  try {
    const githubUrl =
      `https://api.github.com/repos/${CONFIG.GITHUB_REPO_OWNER}/${CONFIG.GITHUB_REPO_NAME}/contents/whitelist.json?ref=${encodeURIComponent(CONFIG.GITHUB_BRANCH)}`;

    const res = await fetch(githubUrl, {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${CONFIG.GITHUB_TOKEN}`,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "SpotUI-Cloudflare-Worker"
      }
    });

    if (!res.ok) {
      const errorText = await safeText(res);
      console.error(`GitHub whitelist GET failed: ${res.status} ${errorText}`);
      throw new Error(`GitHub GET failed: ${res.status}`);
    }

    const fileData = await res.json();
    if (!fileData.content) {
      throw new Error("GitHub response has no file content");
    }

    const content = base64ToUtf8(fileData.content);
    const whitelist = JSON.parse(content);

    // עדכון המטמון בזיכרון אך ורק לאחר קריאה ופרסור מוצלחים מ-GitHub
    memoryWhitelist = whitelist;
    memoryWhitelistFetchedAt = Date.now();

    return whitelistResponse(whitelist, {
      cacheSource: "github-api-fresh",
      requestId
    });
  } catch (err) {
    console.error("GitHub whitelist read error:", err);

    // ------------------------------------------------------------------------
    // Stale Fallback במקרה של בעיית רשת זמנית או חריגה מ-GitHub API
    // ------------------------------------------------------------------------
    if (memoryWhitelist) {
      return whitelistResponse(memoryWhitelist, {
        cacheSource: "memory-stale",
        requestId,
        stale: true
      });
    }

    return jsonResponse(
      { ok: false, error: "sync_failed" },
      502,
      { "X-Cache-Source": "none", "X-Request-Id": requestId }
    );
  }
}

// ==============================================================================
// יצירת תשובת Whitelist עם כותרי Observable Headers
// ==============================================================================
function whitelistResponse(whitelist, { cacheSource, requestId, stale = false }) {
  const version = Number(whitelist?.version || 0);
  const ageMs =
    memoryWhitelistFetchedAt > 0 ? Math.max(0, Date.now() - memoryWhitelistFetchedAt) : 0;

  const headers = {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-cache, no-store, must-revalidate",
    "Access-Control-Allow-Origin": "*",
    "X-Cache-Source": cacheSource,
    "X-Whitelist-Version": String(version),
    "X-Whitelist-Age-Ms": String(ageMs),
    "X-Request-Id": requestId,
    "X-Whitelist-Stale": stale ? "true" : "false"
  };

  return new Response(JSON.stringify(whitelist, null, 2), {
    status: 200,
    headers
  });
}

// ==============================================================================
// טיפול בלחיצה בטלגרם (רץ ברקע דרך waitUntil)
// ==============================================================================
async function handleTelegramCallback(query) {
  const queryId = query.id;
  const msg = query.message;
  if (!msg) return;

  const chatId = msg.chat.id;
  const msgId = msg.message_id;
  const data = query.data || "";
  const text = msg.text || "";
  const from = query.from || {};
  const userMention = from.username
    ? `@${from.username}`
    : [from.first_name, from.last_name].filter(Boolean).join(" ") || "Admin";

  // מניעת לחיצות כפולות באותו Isolate
  if (callbackClaimed.has(queryId)) {
    console.log(`Duplicate callback ignored: ${queryId}`);
    return;
  }
  callbackClaimed.add(queryId);
  if (callbackClaimed.size > 500) callbackClaimed.clear();

  const isApproveArtist = data.startsWith("appr:art");
  const isApproveTrack  = data.startsWith("appr:trk");
  const isRemoveArtist  = data.startsWith("rem:art");
  const isRemoveTrack   = data.startsWith("rem:trk");

  if (!isApproveArtist && !isApproveTrack && !isRemoveArtist && !isRemoveTrack) {
    await sendTelegram("answerCallbackQuery", {
      callback_query_id: queryId,
      text: "פעולה לא מוכרת",
      show_alert: false
    });
    return;
  }

  const isApprove = isApproveArtist || isApproveTrack;

  // 1. Fast Telegram ACK (מכבה את גלגל הטעינה בטלגרם בתוך 20ms!)
  await sendTelegram("answerCallbackQuery", {
    callback_query_id: queryId,
    text: isApprove ? "⏳ מאשר ומוסיף לרשימה..." : "⏳ מסיר מרשימת ההיתר...",
    show_alert: false
  });

  // חילוץ פרטים
  const parts = data.split(":");
  let spotifyId = parts.length >= 3 ? parts[2].trim() : "";
  let artistName = "";
  let trackTitle = "";

  const artistMatch = text.match(/(?:אמן|Artist):\s*([^\n\r*]+)/i);
  if (artistMatch) artistName = artistMatch[1].trim();

  const trackMatch = text.match(/(?:שיר|Track):\s*([^\n\r*]+)/i);
  if (trackMatch) trackTitle = trackMatch[1].trim();

  const idMatch = text.match(/(?:מזהה ספוטיפיי|ID):\s*`?([a-zA-Z0-9]+)`?/i);
  if (!spotifyId && idMatch) spotifyId = idMatch[1].trim();

  const action = isApproveArtist
    ? "approve_artist"
    : isRemoveArtist
    ? "block_artist"
    : isApproveTrack
    ? "approve_track"
    : "block_track";

  // 2. הסרת כפתורי הפעולה ועדכון ההודעה בטלגרם (<200ms)
  const originalKeyboard = (msg.reply_markup && msg.reply_markup.inline_keyboard) || [];
  const urlButtons = extractUrlButtons(originalKeyboard);

  await sendTelegram("editMessageReplyMarkup", {
    chat_id: chatId,
    message_id: msgId,
    reply_markup: { inline_keyboard: urlButtons }
  });

  const actionStatusText = isApprove
    ? `✅ *אושר ונוסף לרשימה הכשרה על ידי ${userMention}!*`
    : `❌ *הוסר מרשימת ההיתר ונחסם על ידי ${userMention}!*`;

  const updatedText = `${text}\n\n━━━━━━━━━━━━━━━━━━━━\n${actionStatusText}`;
  await sendTelegram("editMessageText", {
    chat_id: chatId,
    message_id: msgId,
    text: updatedText,
    parse_mode: "Markdown",
    reply_markup: { inline_keyboard: urlButtons }
  });

  // 3. עדכון ב-GitHub
  try {
    await updateGitHubWhitelistWithRetry({
      action,
      spotifyId,
      artistName,
      trackTitle,
      userMention
    });

    // 4. סנכרון ל-Google Apps Script עבור מכשירים ישנים
    if (CONFIG.GAS_SYNC_URL) {
      try {
        const syncUrl = `${CONFIG.GAS_SYNC_URL}?action=${encodeURIComponent(action)}&id=${encodeURIComponent(spotifyId)}&name=${encodeURIComponent(artistName)}&title=${encodeURIComponent(trackTitle)}&token=${encodeURIComponent(CONFIG.ADMIN_API_KEY)}`;
        await fetch(syncUrl).catch(e => console.error("GAS sync error:", e));
      } catch (gasErr) {
        console.error("GAS sync trigger error:", gasErr);
      }
    }
  } catch (err) {
    console.error("GitHub update failed:", err);
    await sendTelegram("editMessageText", {
      chat_id: chatId,
      message_id: msgId,
      text: `${text}\n\n❌ *שגיאה בעדכון ב-GitHub: ${escapeMarkdown(String(err?.message || err))}*`,
      reply_markup: { inline_keyboard: originalKeyboard }
    });
  }
}

// ==============================================================================
// מנגנון כתיבה מול GitHub (PUT) עם הגנת 409 Retry ועדכון זיכרון לאחר הצלחה
// ==============================================================================
async function updateGitHubWhitelistWithRetry(params) {
  const isApprove = params.action.startsWith("approve");
  const status = isApprove ? "approved" : "blocked";
  const commitSubject = params.artistName || params.trackTitle || params.spotifyId || "item";
  const commitMessage = `${isApprove ? "Approve" : "Remove"} ${commitSubject} via ${params.userMention}`;

  for (let attempt = 1; attempt <= 3; attempt++) {
    // 1. קבלת ה-SHA העדכני מ-GitHub API
    const getRes = await fetch(
      `https://api.github.com/repos/${CONFIG.GITHUB_REPO_OWNER}/${CONFIG.GITHUB_REPO_NAME}/contents/whitelist.json?ref=${encodeURIComponent(CONFIG.GITHUB_BRANCH)}`,
      {
        method: "GET",
        headers: {
          "Authorization": `Bearer ${CONFIG.GITHUB_TOKEN}`,
          "Accept": "application/vnd.github+json",
          "X-GitHub-Api-Version": "2022-11-28",
          "User-Agent": "SpotUI-Cloudflare-Worker"
        }
      }
    );

    if (!getRes.ok) {
      const errorText = await safeText(getRes);
      throw new Error(`GitHub GET failed: ${getRes.status} ${errorText}`);
    }

    const fileData = await getRes.json();
    const currentSha = fileData.sha;
    const jsonString = base64ToUtf8(fileData.content);
    const whitelist = JSON.parse(jsonString);

    // 2. עדכון המבנה
    const now = new Date().toISOString();
    const notes = `${status} via telegram`;

    if (params.action.includes("artist")) {
      upsertArtist(whitelist, params.spotifyId, params.artistName, status, now, notes);
    } else {
      upsertTrack(whitelist, params.spotifyId, params.trackTitle, params.artistName, status, now, notes);
    }

    whitelist.schema_version = 2;
    whitelist.version = Number(whitelist.version || 0) + 1;
    whitelist.last_updated = now;

    // 3. הכנת ה-JSON לדחיפה
    const updatedJsonString = JSON.stringify(whitelist, null, 2);
    const base64Content = utf8ToBase64(updatedJsonString);

    // 4. ביצוע ה-PUT ל-GitHub
    const putRes = await fetch(
      `https://api.github.com/repos/${CONFIG.GITHUB_REPO_OWNER}/${CONFIG.GITHUB_REPO_NAME}/contents/whitelist.json`,
      {
        method: "PUT",
        headers: {
          "Authorization": `Bearer ${CONFIG.GITHUB_TOKEN}`,
          "Accept": "application/vnd.github+json",
          "Content-Type": "application/json",
          "X-GitHub-Api-Version": "2022-11-28",
          "User-Agent": "SpotUI-Cloudflare-Worker"
        },
        body: JSON.stringify({
          message: commitMessage,
          content: base64Content,
          sha: currentSha,
          branch: CONFIG.GITHUB_BRANCH
        })
      }
    );

    // 5. עדכון הזיכרון אך ורק לאחר שה-PUT הצליח ב-100%!
    if (putRes.ok) {
      memoryWhitelist = whitelist;
      memoryWhitelistFetchedAt = Date.now();
      console.log(`Whitelist updated successfully. version=${whitelist.version}`);
      return true;
    }

    // טיפול בהתנגשות (409 Conflict)
    if (putRes.status === 409) {
      console.log(`GitHub 409 conflict on attempt ${attempt}`);
      await sleep(300 * attempt);
      continue;
    }

    const errText = await safeText(putRes);
    throw new Error(`GitHub PUT failed (${putRes.status}): ${errText}`);
  }

  throw new Error("GitHub PUT failed after 3 attempts");
}

// ==============================================================================
// פונקציות עזר (Telegram & Data)
// ==============================================================================
async function sendTelegram(method, payload) {
  try {
    const res = await fetch(`https://api.telegram.org/bot${CONFIG.TELEGRAM_BOT_TOKEN}/${method}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!res.ok && payload.parse_mode) {
      const retryPayload = { ...payload };
      delete retryPayload.parse_mode;
      await fetch(`https://api.telegram.org/bot${CONFIG.TELEGRAM_BOT_TOKEN}/${method}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(retryPayload)
      });
    }
  } catch (e) {
    console.error(`Telegram ${method} error:`, e);
  }
}

function extractUrlButtons(keyboard) {
  const res = [];
  for (const row of keyboard) {
    const newRow = row.filter(btn => btn.url);
    if (newRow.length) res.push(newRow);
  }
  return res;
}

function norm(s) {
  return String(s || "").trim().toLowerCase().replace(/\s+/g, " ");
}

function upsertArtist(wl, id, name, status, now, notes) {
  if (!wl.artists) wl.artists = [];
  const cleanId = String(id || "").trim();
  const cleanName = norm(name);

  let target = wl.artists.find(
    a => (cleanId && a.id === cleanId) || (cleanName && norm(a.canonical_name || a.name) === cleanName)
  );
  if (!target) {
    target = {
      id: cleanId,
      canonical_name: name || "",
      aliases: name ? [name] : [],
      status
    };
    wl.artists.unshift(target);
  } else if (name && !target.aliases?.includes(name)) {
    target.aliases = target.aliases || [];
    target.aliases.push(name);
  }

  target.id = cleanId || target.id || "";
  target.canonical_name = target.canonical_name || name || "";
  target.status = status;
  target.notes = notes;
  target.updated_at = now;
}

function upsertTrack(wl, id, title, artist, status, now, notes) {
  if (!wl.tracks) wl.tracks = [];
  const cleanId = String(id || "").trim();
  const normTitle = norm(title);

  let target = wl.tracks.find(
    t => (cleanId && t.id === cleanId) || (normTitle && norm(t.title) === normTitle)
  );
  if (!target) {
    target = { id: cleanId, title: title || "", artist: artist || "", status };
    wl.tracks.unshift(target);
  }

  target.id = cleanId || target.id || "";
  target.title = target.title || title || "";
  target.artist = target.artist || artist || "";
  target.status = status;
  target.notes = notes;
  target.updated_at = now;
}

function utf8ToBase64(str) {
  const bytes = new TextEncoder().encode(str);
  let binary = "";
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

function base64ToUtf8(b64) {
  const binary = atob(b64.replace(/\s/g, ""));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return new TextDecoder("utf-8").decode(bytes);
}

async function safeText(response) {
  try {
    return await response.text();
  } catch {
    return "";
  }
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function jsonResponse(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, User-Agent, X-Requested-With",
      ...extraHeaders
    }
  });
}

function escapeMarkdown(value) {
  return String(value || "").replace(/([_*[\]()~`>#+\-=|{}.!])/g, "\\$1");
}

// ==============================================================================
// TELEMETRY & LIVE ANALYTICS ENGINE
// ==============================================================================

const ONLINE_WINDOW_MS = 120 * 1000; // 2 minutes window for active listeners

async function handleTelemetryPost(request, env, ctx) {
  try {
    const data = await request.json();
    const userId = String(data.userId || "").trim();
    if (!userId) {
      return jsonResponse({ ok: false, error: "missing_user_id" }, 400);
    }

    const now = Date.now();
    telemetryStats.totalUsersSeen.add(userId);

    const isPlaying = Boolean(data.isPlaying);
    const track = data.track || {};
    const secondsDelta = Number(data.secondsDelta) || 0;
    const totalSeconds = Number(data.totalSecondsListened) || 0;
    const appVersion = String(data.appVersion || "1.0");

    let session = telemetrySessions.get(userId);
    if (!session) {
      session = {
        userId,
        firstSeen: now,
        lastSeen: now,
        isPlaying,
        track,
        totalSeconds,
        appVersion
      };
      telemetrySessions.set(userId, session);
    } else {
      session.lastSeen = now;
      session.isPlaying = isPlaying;
      if (track.title) session.track = track;
      if (totalSeconds > session.totalSeconds) session.totalSeconds = totalSeconds;
      session.appVersion = appVersion;
    }

    // Top tracks & top artists counter
    if (isPlaying && track.title && track.artist && !track.isPodcast) {
      const trackKey = `${track.title} - ${track.artist}`.trim();
      const existingT = telemetryStats.topTracks.get(trackKey) || { title: track.title, artist: track.artist, count: 0 };
      if (data.event === "play" || data.event === "track_change") {
        existingT.count += 1;
        telemetryStats.topTracks.set(trackKey, existingT);

        const artistKey = track.artist.trim();
        const aCount = (telemetryStats.topArtists.get(artistKey) || 0) + 1;
        telemetryStats.topArtists.set(artistKey, aCount);
      }
    }

    // Async sync to Google Apps Script for persistent sheet records
    if (CONFIG.GAS_SYNC_URL && (data.event === "play" || (secondsDelta > 0 && Math.random() < 0.2))) {
      ctx.waitUntil(
        fetch(CONFIG.GAS_SYNC_URL, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            type: "telemetry",
            userId,
            event: data.event,
            track,
            totalSecondsListened: session.totalSeconds,
            timestamp: now
          })
        }).catch(err => console.log("GAS sync error:", err))
      );
    }

    return jsonResponse({ ok: true, activeUsers: getActiveUsersCount() }, 200);
  } catch (err) {
    return jsonResponse({ ok: false, error: String(err?.message || err) }, 500);
  }
}

function getActiveUsersCount() {
  const cutoff = Date.now() - ONLINE_WINDOW_MS;
  let count = 0;
  for (const s of telemetrySessions.values()) {
    if (s.lastSeen >= cutoff) count++;
  }
  return count;
}

function computeAnalytics() {
  const now = Date.now();
  const cutoff = now - ONLINE_WINDOW_MS;
  const activeUsers = [];
  let totalSecondsAllUsers = 0;

  for (const s of telemetrySessions.values()) {
    totalSecondsAllUsers += s.totalSeconds || 0;
    if (s.lastSeen >= cutoff) {
      activeUsers.push({
        userId: "משתמש " + s.userId.slice(-4),
        isPlaying: s.isPlaying,
        trackTitle: s.track?.title || "ללא שיר כרגע",
        artistName: s.track?.artist || "",
        isPodcast: Boolean(s.track?.isPodcast),
        lastSeenSecondsAgo: Math.max(0, Math.round((now - s.lastSeen) / 1000)),
        totalHoursListened: Math.round(((s.totalSeconds || 0) / 3600) * 10) / 10
      });
    }
  }

  const totalUsers = Math.max(telemetryStats.totalUsersSeen.size, telemetrySessions.size, 1);
  const totalHours = Math.round((totalSecondsAllUsers / 3600) * 10) / 10;
  const avgHours = Math.round((totalHours / totalUsers) * 10) / 10;

  const topTracksList = Array.from(telemetryStats.topTracks.values())
    .sort((a, b) => b.count - a.count)
    .slice(0, 10);

  const topArtistsList = Array.from(telemetryStats.topArtists.entries())
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 10);

  return {
    onlineUsers: activeUsers.length,
    totalUsers,
    totalListeningHours: totalHours,
    avgListeningHoursPerUser: avgHours,
    currentlyPlaying: activeUsers.filter(u => u.isPlaying && u.trackTitle !== "ללא שיר כרגע"),
    activeSessions: activeUsers,
    topTracks: topTracksList,
    topArtists: topArtistsList,
    updatedAt: new Date().toISOString()
  };
}

function handleGetAnalytics() {
  return jsonResponse(computeAnalytics(), 200, {
    "Cache-Control": "no-cache, no-store, must-revalidate"
  });
}

function handleGetDashboard() {
  const html = getDashboardHtml();
  return new Response(html, {
    status: 200,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-cache, no-store, must-revalidate"
    }
  });
}

function getDashboardHtml() {
  return `<!DOCTYPE html>
<html lang="he" dir="rtl">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>SpotUI Kosher - לוח מדדים ופעילות בזמן אמת</title>
  <link rel="icon" href="data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 100%22><text y=%22.9em%22 font-size=%2290%22>🎧</text></svg>">
  <style>
    :root {
      --bg-base: #121212;
      --bg-card: #181818;
      --bg-card-hover: #222222;
      --spotify-green: #1DB954;
      --spotify-green-hover: #1ed760;
      --text-main: #FFFFFF;
      --text-sub: #B3B3B3;
      --border-color: rgba(255, 255, 255, 0.08);
      --pulse-glow: rgba(29, 185, 84, 0.4);
    }
    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    }
    body {
      background-color: var(--bg-base);
      color: var(--text-main);
      padding: 24px 16px;
      direction: rtl;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
    }
    .container {
      width: 100%;
      max-width: 1100px;
    }
    header {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 16px;
      padding-bottom: 24px;
      border-bottom: 1px solid var(--border-color);
      margin-bottom: 28px;
    }
    .header-title-box h1 {
      font-size: 26px;
      font-weight: 800;
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .header-title-box p {
      color: var(--text-sub);
      font-size: 14px;
      margin-top: 4px;
    }
    .live-badge {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      background: rgba(29, 185, 84, 0.15);
      border: 1px solid var(--spotify-green);
      color: var(--spotify-green);
      padding: 8px 16px;
      border-radius: 50px;
      font-weight: 700;
      font-size: 14px;
    }
    .pulsing-dot {
      width: 10px;
      height: 10px;
      background-color: var(--spotify-green);
      border-radius: 50%;
      box-shadow: 0 0 0 0 var(--pulse-glow);
      animation: pulse 1.8s infinite;
    }
    @keyframes pulse {
      0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(29, 185, 84, 0.7); }
      70% { transform: scale(1); box-shadow: 0 0 0 10px rgba(29, 185, 84, 0); }
      100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(29, 185, 84, 0); }
    }
    .stats-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 16px;
      margin-bottom: 32px;
    }
    .stat-card {
      background: var(--bg-card);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 20px;
      transition: transform 0.2s ease, border-color 0.2s ease;
      position: relative;
      overflow: hidden;
    }
    .stat-card:hover {
      transform: translateY(-2px);
      border-color: rgba(255, 255, 255, 0.2);
    }
    .stat-card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      color: var(--text-sub);
      font-size: 13px;
      font-weight: 600;
      margin-bottom: 12px;
    }
    .stat-icon {
      font-size: 20px;
    }
    .stat-value {
      font-size: 36px;
      font-weight: 800;
      color: var(--text-main);
      letter-spacing: -0.5px;
    }
    .stat-sub {
      color: var(--text-sub);
      font-size: 12px;
      margin-top: 6px;
    }
    .highlight-green {
      color: var(--spotify-green);
    }
    .section-title {
      font-size: 20px;
      font-weight: 700;
      margin-bottom: 16px;
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .live-feed-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
      gap: 16px;
      margin-bottom: 36px;
    }
    .now-playing-card {
      background: var(--bg-card);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 16px;
      display: flex;
      align-items: center;
      gap: 14px;
      position: relative;
    }
    .equalizer {
      display: flex;
      align-items: flex-end;
      gap: 3px;
      height: 24px;
      width: 20px;
    }
    .eq-bar {
      width: 4px;
      background-color: var(--spotify-green);
      border-radius: 2px;
      animation: soundWave 1.2s infinite ease-in-out;
    }
    .eq-bar:nth-child(1) { height: 18px; animation-delay: 0.1s; }
    .eq-bar:nth-child(2) { height: 24px; animation-delay: 0.3s; }
    .eq-bar:nth-child(3) { height: 12px; animation-delay: 0.2s; }
    @keyframes soundWave {
      0%, 100% { height: 6px; }
      50% { height: 22px; }
    }
    .track-info {
      flex: 1;
      min-width: 0;
    }
    .track-title {
      font-size: 15px;
      font-weight: 700;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .track-artist {
      font-size: 13px;
      color: var(--text-sub);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      margin-top: 2px;
    }
    .user-tag {
      font-size: 11px;
      color: var(--text-sub);
      margin-top: 4px;
    }
    .charts-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
      gap: 20px;
      margin-bottom: 40px;
    }
    .chart-box {
      background: var(--bg-card);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 20px;
    }
    .chart-box h3 {
      font-size: 16px;
      margin-bottom: 16px;
      color: var(--text-main);
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .chart-list {
      list-style: none;
    }
    .chart-item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 10px 0;
      border-bottom: 1px solid rgba(255, 255, 255, 0.05);
      font-size: 14px;
    }
    .chart-item:last-child {
      border-bottom: none;
    }
    .chart-rank {
      font-weight: 800;
      width: 28px;
      color: var(--text-sub);
    }
    .chart-rank.top1 { color: #FFD700; }
    .chart-rank.top2 { color: #C0C0C0; }
    .chart-rank.top3 { color: #CD7F32; }
    .chart-details {
      flex: 1;
      margin: 0 10px;
      overflow: hidden;
    }
    .chart-title {
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .chart-sub {
      font-size: 12px;
      color: var(--text-sub);
    }
    .chart-plays {
      font-size: 12px;
      background: rgba(255, 255, 255, 0.08);
      padding: 4px 10px;
      border-radius: 20px;
      font-weight: 600;
    }
    .empty-state {
      padding: 32px;
      text-align: center;
      color: var(--text-sub);
      background: var(--bg-card);
      border-radius: 12px;
      border: 1px dashed var(--border-color);
      grid-column: 1 / -1;
    }
    footer {
      margin-top: auto;
      text-align: center;
      color: var(--text-sub);
      font-size: 13px;
      padding: 20px 0;
      border-top: 1px solid var(--border-color);
      width: 100%;
    }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="header-title-box">
        <h1><span>🎧</span> SpotUI Kosher — לוח פעילות חי</h1>
        <p>מעקב משתמשים בזמן אמת, זמני האזנה וסטטיסטיקות שידור חי</p>
      </div>
      <div class="live-badge">
        <span class="pulsing-dot"></span>
        <span id="liveStatusText">שידור חי (מתעדכן כל 5 שניות)</span>
      </div>
    </header>

    <!-- 4 כרטיסי מדדי מפתח -->
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-card-header">
          <span>מחוברים עכשיו (Live)</span>
          <span class="stat-icon">🟢</span>
        </div>
        <div class="stat-value highlight-green" id="statOnline">--</div>
        <div class="stat-sub">משתמשים פעילים ב-2 דקות אחרונות</div>
      </div>

      <div class="stat-card">
        <div class="stat-card-header">
          <span>ממוצע שעות למשתמש</span>
          <span class="stat-icon">⏱️</span>
        </div>
        <div class="stat-value" id="statAvgHours">--</div>
        <div class="stat-sub">זמן האזנה ממוצע לכל משתמש</div>
      </div>

      <div class="stat-card">
        <div class="stat-card-header">
          <span>סך שעות האזנה</span>
          <span class="stat-icon">🎧</span>
        </div>
        <div class="stat-value" id="statTotalHours">--</div>
        <div class="stat-sub">מצטבר בכלל מכשירי האפליקציה</div>
      </div>

      <div class="stat-card">
        <div class="stat-card-header">
          <span>סך משתמשים ייחודיים</span>
          <span class="stat-icon">👥</span>
        </div>
        <div class="stat-value" id="statTotalUsers">--</div>
        <div class="stat-sub">מכשירים ייחודיים שחוברו</div>
      </div>
    </div>

    <!-- מתנגן עכשיו בשידור חי -->
    <div class="section-title">
      <span>🎵</span>
      <span>מתנגן עכשיו בשידור חי</span>
    </div>
    <div class="live-feed-grid" id="nowPlayingFeed">
      <div class="empty-state">טוען נתונים בשידור חי...</div>
    </div>

    <!-- מצעד שירים ואמנים מובילים -->
    <div class="charts-grid">
      <div class="chart-box">
        <h3><span>🔥</span> השירים המושמעים ביותר</h3>
        <ul class="chart-list" id="topTracksList">
          <li class="empty-state">אין עדיין נתונים</li>
        </ul>
      </div>

      <div class="chart-box">
        <h3><span>🎤</span> האמנים המובילים</h3>
        <ul class="chart-list" id="topArtistsList">
          <li class="empty-state">אין עדיין נתונים</li>
        </ul>
      </div>
    </div>

    <footer>
      SpotUI Kosher Live Telemetry Engine &bull; פועל בענן Cloudflare & Google Apps Script
    </footer>
  </div>

  <script>
    async function fetchAnalytics() {
      try {
        const res = await fetch('/api/analytics', { cache: 'no-store' });
        if (!res.ok) throw new Error('Network error: ' + res.status);
        const data = await res.json();
        renderData(data);
      } catch (err) {
        console.error('Fetch error:', err);
      }
    }

    function renderData(data) {
      document.getElementById('statOnline').textContent = data.onlineUsers ?? 0;
      document.getElementById('statAvgHours').textContent = (data.avgListeningHoursPerUser ?? 0) + ' שעות';
      document.getElementById('statTotalHours').textContent = (data.totalListeningHours ?? 0) + ' שעות';
      document.getElementById('statTotalUsers').textContent = data.totalUsers ?? 0;

      const feed = document.getElementById('nowPlayingFeed');
      const playing = data.currentlyPlaying || [];
      if (playing.length === 0) {
        feed.innerHTML = '<div class="empty-state">אין כרגע שירים מתנגנים בשידור חי ברגע זה</div>';
      } else {
        feed.innerHTML = playing.map(p => \`
          <div class="now-playing-card">
            <div class="equalizer">
              <div class="eq-bar"></div>
              <div class="eq-bar"></div>
              <div class="eq-bar"></div>
            </div>
            <div class="track-info">
              <div class="track-title">\${escapeHtml(p.trackTitle)}</div>
              <div class="track-artist">\${escapeHtml(p.artistName)}</div>
              <div class="user-tag">\${escapeHtml(p.userId)} &bull; לפני \${p.lastSeenSecondsAgo} שניות &bull; צבר \${p.totalHoursListened} שעות</div>
            </div>
          </div>
        \`).join('');
      }

      const topTracks = data.topTracks || [];
      const tracksList = document.getElementById('topTracksList');
      if (topTracks.length === 0) {
        tracksList.innerHTML = '<li class="empty-state">אין עדיין נתוני השמעות</li>';
      } else {
        tracksList.innerHTML = topTracks.map((t, idx) => {
          const rankClass = idx === 0 ? 'top1' : idx === 1 ? 'top2' : idx === 2 ? 'top3' : '';
          return \`
            <li class="chart-item">
              <span class="chart-rank \${rankClass}">#\${idx + 1}</span>
              <div class="chart-details">
                <div class="chart-title">\${escapeHtml(t.title)}</div>
                <div class="chart-sub">\${escapeHtml(t.artist)}</div>
              </div>
              <span class="chart-plays">\${t.count} השמעות</span>
            </li>
          \`;
        }).join('');
      }

      const topArtists = data.topArtists || [];
      const artistsList = document.getElementById('topArtistsList');
      if (topArtists.length === 0) {
        artistsList.innerHTML = '<li class="empty-state">אין עדיין נתוני אמנים</li>';
      } else {
        artistsList.innerHTML = topArtists.map((a, idx) => {
          const rankClass = idx === 0 ? 'top1' : idx === 1 ? 'top2' : idx === 2 ? 'top3' : '';
          return \`
            <li class="chart-item">
              <span class="chart-rank \${rankClass}">#\${idx + 1}</span>
              <div class="chart-details">
                <div class="chart-title">\${escapeHtml(a.name)}</div>
              </div>
              <span class="chart-plays">\${a.count} השמעות</span>
            </li>
          \`;
        }).join('');
      }
    }

    function escapeHtml(str) {
      if (!str) return '';
      return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
    }

    fetchAnalytics();
    setInterval(fetchAnalytics, 5000);
  </script>
</body>
</html>\`;
}

