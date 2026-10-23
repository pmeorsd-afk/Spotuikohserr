// Search category artwork only: never grant a Home image, artist, track or result image.
import { homeImageUrl, homeImageKey } from "./home_image_approval.mjs";

export function isSearchImageCallback(data) {
  return /^(appr|deny|rem):search:/.test(data || "");
}

export async function searchCallbackTarget(query, channelId) {
  const match = /^(appr|deny|rem):search:([a-f0-9]{48})$/.exec(query.data || "");
  const msg = query.message;
  if (!match || !msg || String(msg.chat?.id) !== String(channelId) || !Number.isSafeInteger(query.from?.id)) return null;
  const buttons = (msg.reply_markup?.inline_keyboard || []).flat();
  if (!buttons.some(b => b.callback_data === query.data)) return null;
  const images = buttons.filter(b => b.text === "🖼️ הצגת התמונה" && b.url);
  if (images.length !== 1) return null;
  const url = homeImageUrl(images[0].url);
  if (!url || await homeImageKey(url) !== match[2]) return null;
  return { action: match[1] === "appr" ? "approve_search_image" : "block_search_image", imageUrl: url, imageId: match[2] };
}

export async function upsertSearchImage(whitelist, params, now) {
  const url = homeImageUrl(params.imageUrl);
  if (!url || await homeImageKey(url) !== params.imageId || !["approve_search_image", "block_search_image"].includes(params.action)) {
    throw new Error("Invalid Search image approval");
  }
  const entries = Array.isArray(whitelist.search_images) ? whitelist.search_images : [];
  whitelist.search_images = entries.filter(entry => entry.url !== url && entry.id !== params.imageId);
  whitelist.search_images.push({
    id: params.imageId, url, scope: "search",
    status: params.action === "approve_search_image" ? "approved" : "blocked",
    updated_at: now,
  });
}
