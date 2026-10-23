// Home-only grants never enter the artist/track whitelist or the legacy GAS sync.
export function homeImageUrl(raw) {
  if (typeof raw !== "string") return null;
  const value = raw.trim();
  if (!value || value.length > 2048 || /[\s\x00-\x1f\x7f]/u.test(value)) return null;
  try {
    const parsed = new URL(value);
    return value.startsWith("https://") && parsed.hostname.includes(".") && !parsed.username &&
      !parsed.password && !parsed.hash && !value.includes("#") ? value : null;
  } catch { return null; }
}

export async function homeImageKey(url) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(url));
  return [...new Uint8Array(digest).slice(0, 24)].map(b => b.toString(16).padStart(2, "0")).join("");
}

export function isHomeImageCallback(data) {
  return /^(appr|deny|rem):home:/.test(data || "");
}

export function homeWebhookAllowed(request, secret) {
  return typeof secret === "string" && secret.length >= 32 &&
    request.headers.get("X-Telegram-Bot-Api-Secret-Token") === secret;
}

export async function homeCallbackTarget(query, channelId) {
  const match = /^(appr|deny|rem):home:([a-f0-9]{48})$/.exec(query.data || "");
  const msg = query.message;
  if (!match || !msg || String(msg.chat?.id) !== String(channelId) || !Number.isSafeInteger(query.from?.id)) return null;
  const buttons = (msg.reply_markup?.inline_keyboard || []).flat();
  if (!buttons.some(b => b.callback_data === query.data)) return null;
  const images = buttons.filter(b => b.text === "🖼️ הצגת התמונה" && b.url);
  if (images.length !== 1) return null;
  const url = homeImageUrl(images[0].url);
  if (!url || await homeImageKey(url) !== match[2]) return null;
  return { action: match[1] === "appr" ? "approve_home_image" : "block_home_image", imageUrl: url, imageId: match[2] };
}

export async function upsertHomeImage(whitelist, params, now) {
  const url = homeImageUrl(params.imageUrl);
  if (!url || await homeImageKey(url) !== params.imageId || !["approve_home_image", "block_home_image"].includes(params.action)) {
    throw new Error("Invalid Home image approval");
  }
  const entries = Array.isArray(whitelist.home_images) ? whitelist.home_images : [];
  whitelist.home_images = entries.filter(entry => entry.url !== url && entry.id !== params.imageId);
  whitelist.home_images.push({
    id: params.imageId, url, scope: "home",
    status: params.action === "approve_home_image" ? "approved" : "blocked",
    updated_at: now,
  });
}
