// ==============================================================================
// SpotUI Kosher - High Performance Real-Time Telegram Webhook (Cloudflare Worker)
// Features: ~20ms Latency | Whitelist Fast API | Background GitHub & GAS Sync
// ==============================================================================

const CONFIG = {
  TELEGRAM_BOT_TOKEN: "8800365444:AAH2W5JBJhrytzmthZMI1TlmzDTpNWnTlo4",
  TELEGRAM_CHANNEL_ID: "-1004491387106", // @spotifty_kosher
  GITHUB_TOKEN: "YOUR_GITHUB_TOKEN",
  GITHUB_REPO_OWNER: "pmeorsd-afk",
  GITHUB_REPO_NAME: "Spotuikohserr",
  GITHUB_BRANCH: "main",
  // סנכרון אוטומטי למטמון של Google Apps Script עבור גרסאות אפליקציה ישנות
  GAS_SYNC_URL: "https://script.google.com/macros/s/AKfycbxjKBX2VHdyKfkih9EOgTOs5C08iFKqOEOaSeis1Ov1NZPBjR2HEVtMX-aAEricAXpPJw/exec",
  ADMIN_API_KEY: "SPOTUI_ADMIN_SECRET_KEY_2026"
};

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // 1. כפתור הפעלה מהיר בדפדפן להגדרת ה-Webhook בטלגרם
    if (url.pathname === "/setWebhook") {
      const workerUrl = `${url.origin}/`;
      const tgRes = await fetch(`https://api.telegram.org/bot${CONFIG.TELEGRAM_BOT_TOKEN}/setWebhook?url=${encodeURIComponent(workerUrl)}`);
      const tgData = await tgRes.json();
      return new Response(JSON.stringify({ workerUrl, telegramResponse: tgData }, null, 2), {
        headers: { "Content-Type": "application/json; charset=utf-8" }
      });
    }

    // 2. שרת Whitelist מהיר במיוחד (0ms Latency) לאפליקציית SpotUI
    if (request.method === "GET" && (url.pathname === "/whitelist.json" || url.pathname === "/whitelist" || url.pathname === "/exec")) {
      return handleGetWhitelist(request);
    }

    // 3. בדיקת תקינות (Health Check)
    if (request.method === "GET") {
      return new Response("SpotUI Telegram Bot Worker is running 🚀\nWhitelist API: /whitelist.json", {
        status: 200,
        headers: { "Content-Type": "text/plain; charset=utf-8" }
      });
    }

    if (request.method !== "POST") {
      return new Response("Method not allowed", { status: 405 });
    }

    try {
      const update = await request.json();

      // טיפול בלחיצה על כפתור בטלגרם
      if (update.callback_query) {
        // עונים לטלגרם ב-20ms וסוגרים את ה-Spinner מיידית!
        ctx.waitUntil(handleTelegramCallback(update.callback_query));
        return new Response("OK", { status: 200 });
      }

      return new Response("OK", { status: 200 });
    } catch (err) {
      console.error("Worker error:", err);
      return new Response("Error: " + err.message, { status: 500 });
    }
  }
};

// ==============================================================================
// שרת Whitelist מהיר ישירות מ-Cloudflare Edge
// ==============================================================================

async function handleGetWhitelist(request) {
  try {
    const rawUrl = `https://raw.githubusercontent.com/${CONFIG.GITHUB_REPO_OWNER}/${CONFIG.GITHUB_REPO_NAME}/${CONFIG.GITHUB_BRANCH}/whitelist.json?t=${Date.now()}`;
    const res = await fetch(rawUrl, {
      headers: {
        "Accept": "application/json",
        "User-Agent": "SpotUI-Cloudflare-Worker"
      },
      cf: {
        cacheTtl: 10,
        cacheEverything: true
      }
    });

    if (!res.ok) {
      return new Response(JSON.stringify({ ok: false, error: "github_fetch_failed" }), {
        status: 502,
        headers: { "Content-Type": "application/json; charset=utf-8" }
      });
    }

    const data = await res.text();
    return new Response(data, {
      status: 200,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "public, max-age=15, s-maxage=15",
        "Access-Control-Allow-Origin": "*"
      }
    });
  } catch (e) {
    return new Response(JSON.stringify({ ok: false, error: String(e) }), {
      status: 500,
      headers: { "Content-Type": "application/json; charset=utf-8" }
    });
  }
}

// ==============================================================================
// טיפול בלחיצה בטלגרם (רץ ב-Worker ברקע בלי שום עיכוב למשתמש)
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
  const userMention = from.username ? `@${from.username}` : [from.first_name, from.last_name].filter(Boolean).join(" ") || "Admin";

  const isApproveArtist = data.startsWith("appr:art");
  const isApproveTrack  = data.startsWith("appr:trk");
  const isRemoveArtist  = data.startsWith("rem:art");
  const isRemoveTrack   = data.startsWith("rem:trk");

  if (!isApproveArtist && !isApproveTrack && !isRemoveArtist && !isRemoveTrack) {
    await sendTelegram("answerCallbackQuery", { callback_query_id: queryId, text: "פעולה לא מוכרת" });
    return;
  }

  const isApprove = isApproveArtist || isApproveTrack;

  // 1. EARLY ACK מיידי (תוך 20ms!) — מכבה את גלגל הטעינה בטלגרם של המשתמש מיידית!
  const toastText = isApprove ? "⏳ מאשר ומוסיף לרשימה..." : "⏳ מסיר מרשימת ההיתר...";
  await sendTelegram("answerCallbackQuery", { callback_query_id: queryId, text: toastText, show_alert: false });

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

  const action = isApproveArtist ? "approve_artist"
               : isRemoveArtist  ? "block_artist"
               : isApproveTrack   ? "approve_track"
               : "block_track";

  // 2. עדכון מיידי של ההודעה בטלגרם (<200ms) — הסרת הכפתור והצגת V ירוק!
  const originalKeyboard = (msg.reply_markup && msg.reply_markup.inline_keyboard) || [];
  const urlButtons = extractUrlButtons(originalKeyboard);

  // מעלימים את כפתור האישור/הסרה מיד כדי שלא תהיה אפשרות ללחוץ פעמיים
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

  // 3. עדכון ה-Whitelist ב-GitHub (ברקע עם מנגנון Retry נגד התנגשויות)
  try {
    await updateGitHubWhitelistWithRetry({
      action,
      spotifyId,
      artistName,
      trackTitle,
      userMention
    });

    // 4. סנכרון אוטומטי של המטמון ב-Google Apps Script עבור מכשירים ישנים
    if (CONFIG.GAS_SYNC_URL) {
      try {
        const syncUrl = `${CONFIG.GAS_SYNC_URL}?action=${encodeURIComponent(action)}&id=${encodeURIComponent(spotifyId)}&name=${encodeURIComponent(artistName)}&title=${encodeURIComponent(trackTitle)}&token=${encodeURIComponent(CONFIG.ADMIN_API_KEY)}`;
        await fetch(syncUrl).catch(e => console.error("GAS sync fetch error:", e));
      } catch (gasErr) {
        console.error("GAS sync trigger error:", gasErr);
      }
    }
  } catch (err) {
    console.error("GitHub update failed:", err);
    // במקרה של שגיאה נדירה ב-GitHub, נעדכן את ההודעה
    await sendTelegram("editMessageText", {
      chat_id: chatId,
      message_id: msgId,
      text: `${text}\n\n❌ *שגיאה בעדכון ב-GitHub: ${err.message}*`,
      reply_markup: { inline_keyboard: originalKeyboard }
    });
  }
}

// ==============================================================================
// מנגנון סנכרון מול GitHub ב-UTF-8 מלא + מניעת התנגשויות (409 Retry)
// ==============================================================================

async function updateGitHubWhitelistWithRetry(params) {
  const isApprove = params.action.startsWith("approve");
  const status = isApprove ? "approved" : "blocked";
  const commitSubject = params.artistName || params.trackTitle || params.spotifyId || "item";
  const commitMessage = `${isApprove ? "Approve" : "Remove"} ${commitSubject} via ${params.userMention}`;

  for (let attempt = 1; attempt <= 3; attempt++) {
    // 1. קבלת הקובץ וה-SHA העדכני ביותר
    const getRes = await fetch(
      `https://api.github.com/repos/${CONFIG.GITHUB_REPO_OWNER}/${CONFIG.GITHUB_REPO_NAME}/contents/whitelist.json?ref=${CONFIG.GITHUB_BRANCH}`,
      {
        headers: {
          "Authorization": `Bearer ${CONFIG.GITHUB_TOKEN}`,
          "Accept": "application/vnd.github+json",
          "User-Agent": "SpotUI-Cloudflare-Worker"
        }
      }
    );

    if (!getRes.ok) {
      throw new Error(`GitHub GET failed: ${getRes.status}`);
    }

    const fileData = await getRes.json();
    const currentSha = fileData.sha;
    const jsonString = base64ToUtf8(fileData.content);
    const whitelist = JSON.parse(jsonString);

    // 2. עדכון המערך בזכרון
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

    // 3. דחיפה ל-GitHub בקידוד UTF-8 מושלם
    const updatedJsonString = JSON.stringify(whitelist, null, 2);
    const base64Content = utf8ToBase64(updatedJsonString);

    const putRes = await fetch(
      `https://api.github.com/repos/${CONFIG.GITHUB_REPO_OWNER}/${CONFIG.GITHUB_REPO_NAME}/contents/whitelist.json`,
      {
        method: "PUT",
        headers: {
          "Authorization": `Bearer ${CONFIG.GITHUB_TOKEN}`,
          "Accept": "application/vnd.github+json",
          "Content-Type": "application/json",
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

    if (putRes.ok) {
      return true; // הצלחה!
    }

    if (putRes.status === 409) {
      console.log(`GitHub 409 conflict on attempt ${attempt}, retrying in 300ms...`);
      await new Promise(r => setTimeout(r, 300 * attempt));
      continue;
    }

    const errText = await putRes.text();
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
      delete payload.parse_mode; // Fallback אם יש תווים מיוחדים
      await fetch(`https://api.telegram.org/bot${CONFIG.TELEGRAM_BOT_TOKEN}/${method}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
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

  let target = wl.artists.find(a => (cleanId && a.id === cleanId) || (cleanName && norm(a.canonical_name || a.name) === cleanName));
  if (!target) {
    target = { id: cleanId, canonical_name: name || "", aliases: [name].filter(Boolean), status };
    wl.artists.unshift(target);
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

  let target = wl.tracks.find(t => (cleanId && t.id === cleanId) || (normTitle && norm(t.title) === normTitle));
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
