// Telegram callback IDs may themselves contain ':'. Never split away the provider ID.
export function legacyApprovalTarget(query, channel) {
  if (String(query?.message?.chat?.id) !== String(channel)) return null;
  const data = String(query.data || "");
  const match = /^(appr|rem):(art|trk)(?::(.+))?$/.exec(data);
  if (!match || !query.message.reply_markup?.inline_keyboard?.flat()
    .some(button => button.callback_data === data)) return null;
  const text = query.message.text || query.message.caption || "";
  const field = label => text.match(new RegExp(`(?:${label}):\\s*([^\\n\\r]+)`, "i"))?.[1]?.trim() || "";
  const artistName = field("אמן|Artist");
  const trackTitle = field("שיר|Track");
  const spotifyId = (match[3] || field("מזהה ספוטיפיי|ID").replace(/^`|`$/g, "")).trim();
  const track = match[2] === "trk";
  if (spotifyId && !(track ? /^(?:[a-zA-Z0-9]{22}|youtube:[a-zA-Z0-9_-]{11})$/ : /^[a-zA-Z0-9]{22}$/).test(spotifyId)) return null;
  if (!spotifyId && (!artistName || (track && !trackTitle))) return null;
  return { action: `${match[1] === "appr" ? "approve" : "block"}_${track ? "track" : "artist"}`,
    spotifyId, artistName, trackTitle };
}

const norm = value => String(value || "").trim().toLowerCase().replace(/\s+/g, " ");

export function upsertExactTrack(wl, id, title, artist, status, now, notes) {
  const cleanId = String(id || "").trim();
  if (cleanId && !/^(?:[a-zA-Z0-9]{22}|youtube:[a-zA-Z0-9_-]{11})$/.test(cleanId)) throw new Error("Invalid track identity");
  if (!cleanId && (!norm(title) || !norm(artist))) throw new Error("Missing track identity");
  wl.tracks ||= [];
  // Names may enrich a legacy ID-less entry, never replace another recording's ID.
  let target = (cleanId && wl.tracks.find(t => t.id === cleanId)) || wl.tracks.find(t =>
    !String(t.id || "").trim() && norm(title) && norm(artist) && norm(t.title) === norm(title) && norm(t.artist) === norm(artist));
  if (target?.id === cleanId && target?.status === status) return false;
  if (!target) {
    target = { id: cleanId, title: title || "", artist: artist || "", status };
    wl.tracks.unshift(target);
  }
  target.id = cleanId || target.id || "";
  target.title ||= title || "";
  target.artist ||= artist || "";
  Object.assign(target, { status, notes, updated_at: now });
  return true;
}
