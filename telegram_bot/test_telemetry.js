async function test() {
  const postRes = await fetch("https://lingering-brook-93f6.orelgame156.workers.dev/api/telemetry", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      userId: "usr_node_test",
      event: "play",
      isPlaying: true,
      secondsDelta: 10,
      totalSecondsListened: 300,
      track: { title: "שיר בדיקה", artist: "אמן בדיקה" }
    })
  });
  console.log("POST status:", postRes.status, await postRes.json());
  const getRes = await fetch("https://lingering-brook-93f6.orelgame156.workers.dev/api/analytics");
  console.log("GET status:", getRes.status, await getRes.json());
}
test();
