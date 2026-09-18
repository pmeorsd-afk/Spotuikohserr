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
let callbackClaimed = new Set();

// ==============================================================================
// Worker Entry Point
// ==============================================================================
export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // --------------------------------------------------------------------------
    // 1. הגדרת Webhook בטלגרם בלחיצה ישירה מהדפדפן
    // --------------------------------------------------------------------------
    if (url.pathname === "/setWebhook") {
      const workerUrl = `${url.origin}/`;
      const tgRes = await fetch(
        `https://api.telegram.org/bot${CONFIG.TELEGRAM_BOT_TOKEN}/setWebhook?url=${encodeURIComponent(workerUrl)}`
      );
      const tgData = await tgRes.json();
      return jsonResponse({ workerUrl, telegramResponse: tgData });
    }

    // --------------------------------------------------------------------------
    // 2. שרת Whitelist מהיר מבוסס GitHub Contents API
    // --------------------------------------------------------------------------
    if (
      request.method === "GET" &&
      (url.pathname === "/whitelist.json" || url.pathname === "/whitelist" || url.pathname === "/exec")
    ) {
      return handleGetWhitelist(request);
    }

    // --------------------------------------------------------------------------
    // 3. בדיקת תקינות (Health Check)
    // --------------------------------------------------------------------------
    if (request.method === "GET") {
      const ver = memoryWhitelist ? memoryWhitelist.version : "not_cached_yet";
      return new Response(
        `SpotUI Telegram Bot Worker v3.1 is running 🚀\nWhitelist API: /whitelist.json\nCurrent Memory Version: ${ver}`,
        {
          status: 200,
          headers: { "Content-Type": "text/plain; charset=utf-8" }
        }
      );
    }

    // --------------------------------------------------------------------------
    // 4. Telegram Webhook (POST)
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
      ...extraHeaders
    }
  });
}

function escapeMarkdown(value) {
  return String(value || "").replace(/([_*[\]()~`>#+\-=|{}.!])/g, "\\$1");
}
