import test from "node:test";
import assert from "node:assert/strict";
import { registerHooks } from "node:module";
registerHooks({ resolve(specifier, context, nextResolve) {
  return specifier === "cloudflare:workers" ? { url: "data:text/javascript,export class DurableObject {}", shortCircuit: true } : nextResolve(specifier, context);
} });
const { default: worker } = await import("./worker.js");
import { legacyApprovalTarget, upsertExactTrack } from "./track_approval.mjs";
const channel = "-1000000000000";
const env = { TELEGRAM_CHANNEL_ID: channel, TELEGRAM_BOT_TOKEN: "test-token", GITHUB_TOKEN: "test-token", TELEGRAM_WEBHOOK_SECRET: "x".repeat(48) };
let sequence = 0;
function query(id = "youtube:tLTgCQovmpc", title = "אבי הטחול (גרסת ברסלב)-דוד שרר", artist = "דוד שרר", action = "appr:trk") {
  const data = id ? `${action}:${id}` : action;
  const serial = ++sequence;
  return { id: `q-${serial}`, from: { id: 42, username: "test_admin" }, data,
    message: { message_id: serial, chat: { id: Number(channel) },
      text: `🎶 שיר: ${title}\n🎤 אמן: ${artist}\n🆔 מזהה ספוטיפיי: ${id}`,
      reply_markup: { inline_keyboard: [[{ text: "approve", callback_data: data }], [{ text: "cover", url: "https://i.scdn.co/image/exact" }]] } } };
}
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }

test("full provider identity survives both approve and remove callbacks", () => {
  for (const action of ["appr:trk", "rem:trk"]) {
    const target = legacyApprovalTarget(query("youtube:aBcD-_12345", "song", "artist", action), channel);
    assert.equal(target.spotifyId, "youtube:aBcD-_12345");
    assert.equal(target.action, action.startsWith("appr") ? "approve_track" : "block_track");
  }
});
test("legacy text fallback preserves the complete YouTube ID", () => {
  const q = query(); q.data = "appr:trk"; q.message.reply_markup.inline_keyboard[0][0].callback_data = q.data;
  assert.equal(legacyApprovalTarget(q, channel).spotifyId, "youtube:tLTgCQovmpc");
});
test("invalid, truncated, mismatched-chat and detached-button requests cannot grant", () => {
  for (const id of ["youtube", "youtube:", "youtube:short", "youtube:12345678901:extra"]) assert.equal(legacyApprovalTarget(query(id), channel), null);
  assert.equal(legacyApprovalTarget(query(), "other"), null);
  const q = query(); q.message.reply_markup.inline_keyboard = [];
  assert.equal(legacyApprovalTarget(q, channel), null);
});
test("Spotify artist and track IDs remain exact", () => {
  const id = "0rVTBbyS7Etl0lI3bpwo0h";
  assert.equal(legacyApprovalTarget(query(id), channel).spotifyId, id);
  assert.equal(legacyApprovalTarget(query(id, "", "artist", "appr:art"), channel).action, "approve_artist");
});
test("approving multiple YouTube recordings does not overwrite a prior approval", () => {
  const wl = { tracks: [{ id: "youtube", title: "legacy ambiguous", status: "approved" }] };
  for (const id of ["youtube:tLTgCQovmpc", "youtube:aBcD-_12345"]) upsertExactTrack(wl, id, "same title", "same artist", "approved", "now", "test");
  assert.equal(wl.tracks.length, 3);
  assert.deepEqual(new Set(wl.tracks.map(t => t.id)), new Set(["youtube", "youtube:tLTgCQovmpc", "youtube:aBcD-_12345"]));
});
test("same title by different artists and different IDs never coalesces", () => {
  const wl = { tracks: [] };
  upsertExactTrack(wl, "0rVTBbyS7Etl0lI3bpwo0h", "song", "artist A", "approved", "now", "test");
  upsertExactTrack(wl, "1rVTBbyS7Etl0lI3bpwo0h", "song", "artist B", "blocked", "later", "test");
  assert.equal(wl.tracks.length, 2); assert.equal(wl.tracks[1].status, "approved");
});
test("only an exact title AND artist can enrich a legacy ID-less entry", () => {
  const wl = { tracks: [{ id: "", title: "song", artist: "A", status: "approved" }] };
  upsertExactTrack(wl, "", "song", "B", "approved", "now", "test");
  upsertExactTrack(wl, "youtube:tLTgCQovmpc", "song", "A", "approved", "now", "test");
  assert.equal(wl.tracks.length, 2); assert.equal(wl.tracks[1].id, "youtube:tLTgCQovmpc");
  assert.throws(() => upsertExactTrack(wl, "youtube", "song", "A", "approved", "now", "test"));
});

async function harness(options, run) {
  const originalFetch = globalThis.fetch;
  const calls = [], waits = [];
  let wl = structuredClone(options.initial || { version: 10, artists: [], tracks: [], home_images: [{ id: "keep-home" }], search_images: [{ id: "keep-search" }] });
  let sha = 1, puts = 0, edits = 0;
  globalThis.fetch = async (address, init) => {
    address = String(address);
    const payload = init?.body ? JSON.parse(init.body) : undefined;
    const kind = address.includes("api.github.com") ? `github-${init.method}` : address.split("/").at(-1);
    calls.push({ kind, payload });
    if (kind === "getChatMember") return Response.json({ ok: true, result: { status: options.member || "administrator" } });
    if (kind === "github-GET") {
      if (options.getGate) await options.getGate.promise;
      return Response.json({ sha: String(sha), content: Buffer.from(JSON.stringify(wl)).toString("base64") });
    }
    if (kind === "github-PUT") {
      puts++;
      if (options.conflict && puts === 1) {
        wl.tracks.push({ id: "2rVTBbyS7Etl0lI3bpwo0h", title: "external approval", status: "approved" }); sha++;
        return Response.json({}, { status: 409 });
      }
      if (options.failPut) return Response.json({}, { status: 503 });
      assert.equal(payload.sha, String(sha));
      wl = JSON.parse(Buffer.from(payload.content, "base64").toString()); sha++;
      return Response.json({});
    }
    if (kind === "editMessageText" || kind === "editMessageCaption") {
      edits++;
      if (options.editGate) await options.editGate.promise;
      if (options.rateLimit && edits === 1) return Response.json({ ok: false, error_code: 429, parameters: { retry_after: 0 } }, { status: 429 });
    }
    assert.ok(!address.includes("script.google.com"), "an approval must not write the same whitelist twice through GAS");
    return Response.json({ ok: true, result: {} });
  };
  const dispatch = q => worker.fetch(new Request("https://worker.example/", { method: "POST",
    headers: { "Content-Type": "application/json", "X-Telegram-Bot-Api-Secret-Token": options.secret ?? env.TELEGRAM_WEBHOOK_SECRET },
    body: JSON.stringify({ callback_query: q }) }), env, { waitUntil: p => waits.push(p) });
  try { await run({ dispatch, calls, waits, state: () => wl, puts: () => puts }); }
  finally { options.getGate?.resolve(); options.editGate?.resolve(); await Promise.all(waits); globalThis.fetch = originalFetch; }
}

test("five rapid approvals ACK before any slow GitHub write; all exact IDs persist", async () => {
  const gate = deferred();
  await harness({ getGate: gate }, async ({ dispatch, calls, waits, state }) => {
    for (let i = 0; i < 5; i++) {
      const q = query(`youtube:Video00000${i}`);
      const response = await dispatch(q);
      assert.equal(response.status, 200);
      assert.deepEqual(await response.json(), { method: "answerCallbackQuery", callback_query_id: q.id,
        text: "הבקשה נקלטה, שומר את ההחלטה…", cache_time: 0 });
    }
    assert.ok(!calls.some(c => c.kind === "github-PUT" || c.kind.startsWith("editMessage")));
    gate.resolve(); await Promise.all(waits);
    assert.equal(state().tracks.length, 5); assert.equal(state().version, 15);
    assert.equal(calls.filter(c => c.kind === "editMessageText").length, 5);
    assert.ok(!calls.some(c => c.kind === "answerCallbackQuery" || c.kind === "editMessageReplyMarkup"));
    assert.deepEqual(state().home_images, [{ id: "keep-home" }]);
    assert.deepEqual(state().search_images, [{ id: "keep-search" }]);
  });
});
test("GitHub conflict reapplies the approval to the newest file without losing other grants", async () => {
  await harness({ conflict: true }, async ({ dispatch, waits, state, puts }) => {
    await dispatch(query()); await Promise.all(waits);
    assert.equal(puts(), 2); assert.equal(state().tracks.length, 2);
    assert.ok(state().tracks.some(t => t.title === "external approval"));
  });
});
test("failed persistence never announces success or removes retry controls", async () => {
  await harness({ failPut: true }, async ({ dispatch, waits, calls, state }) => {
    const q = query(); await dispatch(q); await Promise.all(waits);
    assert.equal(state().tracks.length, 0);
    const edit = calls.find(c => c.kind === "editMessageText");
    assert.match(edit.payload.text, /ההחלטה לא נשמרה/);
    assert.deepEqual(edit.payload.reply_markup, q.message.reply_markup);
  });
});
test("duplicate clicks and a later retry do not create duplicate commits", async () => {
  const gate = deferred();
  await harness({ getGate: gate }, async ({ dispatch, waits, puts }) => {
    const q = query(); await dispatch(q); await dispatch({ ...q, id: "q-another-click" });
    gate.resolve(); await Promise.all(waits);
    await dispatch({ ...q, id: "q-retry-after-save" }); await Promise.all(waits);
    assert.equal(puts(), 1);
  });
});
test("a slow Telegram edit does not prevent the next item being saved or ACKed", async () => {
  const gate = deferred();
  await harness({ editGate: gate }, async ({ dispatch, waits, puts }) => {
    await dispatch(query());
    await new Promise(r => setImmediate(r));
    assert.equal(puts(), 1);
    const response = await dispatch(query("youtube:aBcD-_12345"));
    assert.equal((await response.json()).method, "answerCallbackQuery");
    await new Promise(r => setImmediate(r));
    assert.equal(puts(), 2);
    gate.resolve(); await Promise.all(waits);
  });
});
test("Telegram 429 retries only the final edit, never the approval write", async () => {
  await harness({ rateLimit: true }, async ({ dispatch, waits, calls, puts }) => {
    await dispatch(query()); await Promise.all(waits);
    assert.equal(puts(), 1); assert.equal(calls.filter(c => c.kind === "editMessageText").length, 2);
  });
});
test("non-admin callback cannot mutate the whitelist or message", async () => {
  await harness({ member: "member" }, async ({ dispatch, waits, calls }) => {
    await dispatch(query()); await Promise.all(waits);
    assert.deepEqual(calls.map(c => c.kind), ["getChatMember"]);
  });
});
test("forged webhook rejected before any network operation", async () => {
  await harness({ secret: "wrong" }, async ({ dispatch, calls }) => {
    assert.equal((await dispatch(query())).status, 403); assert.equal(calls.length, 0);
  });
});
test("caption requests confirm only after durable storage", async () => {
  await harness({}, async ({ dispatch, waits, calls }) => {
    const q = query(); q.message.caption = q.message.text; delete q.message.text;
    await dispatch(q); await Promise.all(waits);
    assert.ok(calls.findIndex(c => c.kind === "github-PUT") < calls.findIndex(c => c.kind === "editMessageCaption"));
  });
});
