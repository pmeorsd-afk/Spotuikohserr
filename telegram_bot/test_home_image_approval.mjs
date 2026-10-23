import test from "node:test";
import assert from "node:assert/strict";
import { registerHooks } from "node:module";
// Only the unused Durable Object runtime is stubbed; the real Worker/router executes below.
registerHooks({ resolve(specifier, context, nextResolve) {
  return specifier === "cloudflare:workers"
    ? { url: "data:text/javascript,export class DurableObject {}", shortCircuit: true }
    : nextResolve(specifier, context);
} });
const { default: worker } = await import("./worker.js");
import { homeImageUrl, homeImageKey, homeCallbackTarget, upsertHomeImage } from "./home_image_approval.mjs";

const url = "https://i.scdn.co/image/exact-Cover?size=300";
const id = await homeImageKey(url);
const channel = "-1000000000000";
const env = { TELEGRAM_CHANNEL_ID: channel, TELEGRAM_BOT_TOKEN: "test-token", GITHUB_TOKEN: "test-token", TELEGRAM_WEBHOOK_SECRET: "x".repeat(48) };
const query = (action = "appr") => ({ id: "query-1", from: { id: 42 }, data: `${action}:home:${id}`,
  message: { message_id: 1, chat: { id: Number(channel) }, text: "request",
    reply_markup: { inline_keyboard: [[{ text: "action", callback_data: `${action}:home:${id}` }], [{ text: "🖼️ הצגת התמונה", url }]] } } });

test("URL validation and exact digest preserve query and case", async () => {
  for (const value of ["", "http://example.com/a", "https://x:y@example.com/a", "file:///a", "https://localhost/a", "https://example.com/a#", "https://example.com/a b"]) assert.equal(homeImageUrl(value), null);
  assert.equal(homeImageUrl(url), url);
  assert.notEqual(await homeImageKey(url + "&v=2"), id);
  assert.notEqual(await homeImageKey(url.toLowerCase()), id);
  assert.ok(Buffer.byteLength(`appr:home:${id}`) <= 64);
});
test("callback validates chat, reviewed URL, button and digest", async () => {
  assert.equal((await homeCallbackTarget(query(), channel)).imageUrl, url);
  assert.equal(await homeCallbackTarget(query(), "wrong"), null);
  const changed = query(); changed.message.reply_markup.inline_keyboard[1][0].url += "&new=1";
  assert.equal(await homeCallbackTarget(changed, channel), null);
  const missing = query(); missing.message.reply_markup.inline_keyboard[0] = [];
  assert.equal(await homeCallbackTarget(missing, channel), null);
});
test("approval and revocation only update home_images", async () => {
  const whitelist = { artists: [{ id: "a" }], tracks: [{ id: "t" }], version: 10 };
  const params = await homeCallbackTarget(query(), channel);
  await upsertHomeImage(whitelist, params, "now");
  await upsertHomeImage(whitelist, params, "now");
  assert.equal(whitelist.home_images.length, 1);
  assert.equal(whitelist.home_images[0].status, "approved");
  await upsertHomeImage(whitelist, { ...params, action: "block_home_image" }, "later");
  assert.equal(whitelist.home_images[0].status, "blocked");
  assert.deepEqual(whitelist.artists, [{ id: "a" }]);
  assert.deepEqual(whitelist.tracks, [{ id: "t" }]);
  assert.equal(whitelist.version, 10);
});

async function runWebhook({ secret = env.TELEGRAM_WEBHOOK_SECRET, member = "administrator", putStatus = 200, callback = query() } = {}) {
  const originalFetch = globalThis.fetch;
  const calls = []; let written;
  globalThis.fetch = async (input, init) => {
    const address = String(input); const payload = init?.body ? JSON.parse(init.body) : undefined;
    calls.push({ kind: address.includes("api.github.com") ? `github-${init.method}` : address.split('/').at(-1), payload });
    if (address.endsWith("/getChatMember")) return Response.json({ ok: true, result: { status: member } });
    if (address.includes("api.github.com")) {
      if (init.method === "PUT") {
        if (putStatus === 200) written = JSON.parse(Buffer.from(payload.content, "base64").toString());
        return Response.json({}, { status: putStatus });
      }
      return Response.json({ sha: "test-sha", content: Buffer.from(JSON.stringify({ artists: [], tracks: [], version: 1 })).toString("base64") });
    }
    return Response.json({ ok: true, result: {} });
  };
  try {
    const waits = [];
    const response = await worker.fetch(new Request("https://worker.example/", { method: "POST",
      headers: { "Content-Type": "application/json", "X-Telegram-Bot-Api-Secret-Token": secret },
      body: JSON.stringify({ callback_query: callback }) }), env, { waitUntil: p => waits.push(p) });
    await Promise.all(waits);
    return { response, calls, written };
  } finally { globalThis.fetch = originalFetch; }
}
test("webhook rejects a forged request before calling Telegram or GitHub", async () => {
  const result = await runWebhook({ secret: "wrong" });
  assert.equal(result.response.status, 403); assert.equal(result.calls.length, 0);
});
test("channel members cannot grant images", async () => {
  const result = await runWebhook({ member: "member" });
  assert.equal(result.written, undefined);
  assert.ok(!result.calls.some(c => c.kind.startsWith("github")));
});
test("durable approval precedes success UI; other whitelist collections stay empty", async () => {
  const result = await runWebhook();
  assert.equal(result.written.version, 2);
  assert.equal(result.written.home_images[0].scope, "home");
  assert.deepEqual(result.written.artists, []); assert.deepEqual(result.written.tracks, []);
  assert.ok(result.calls.findIndex(c => c.kind === "github-PUT") < result.calls.findIndex(c => c.kind === "editMessageText"));
  assert.match(result.calls.find(c => c.kind === "editMessageText").payload.reply_markup.inline_keyboard[0][0].callback_data, /^rem:home:/);
});
test("failed durable write keeps request actionable and does not announce approval", async () => {
  const result = await runWebhook({ putStatus: 503 });
  assert.equal(result.written, undefined);
  assert.ok(!result.calls.some(c => c.kind === "editMessageText" || c.kind === "editMessageReplyMarkup"));
});
test("rejection and revocation persist a blocked Home-only entry", async () => {
  for (const action of ["deny", "rem"]) {
    const result = await runWebhook({ callback: query(action) });
    assert.equal(result.written.home_images[0].status, "blocked");
  }
});
