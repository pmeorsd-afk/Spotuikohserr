// ==============================================================================
// SpotUI Kosher - Live Analytics & Telemetry Server (Node.js)
// Runs locally and serves both the live Dashboard and Telemetry API
// ==============================================================================

const http = require('http');
const url = require('url');

const PORT = process.env.PORT || 8787;
const ONLINE_WINDOW_MS = 180 * 1000; // 3 minutes

const telemetrySessions = new Map();
const telemetryStats = {
  totalUsersSeen: new Set(),
  topTracks: new Map(),
  topArtists: new Map()
};

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

const fs = require('fs');
const path = require('path');

function getDashboardHtml() {
  const htmlPath = path.join(__dirname, '..', 'dashboard_preview.html');
  if (fs.existsSync(htmlPath)) {
    return fs.readFileSync(htmlPath, 'utf8');
  }
  return '<h1>SpotUI Live Dashboard</h1>';
}

const server = http.createServer((req, res) => {
  const parsedUrl = url.parse(req.url, true);
  const pathname = parsedUrl.pathname;

  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, User-Agent, X-Requested-With');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  if (req.method === 'GET' && (pathname === '/' || pathname === '/dashboard' || pathname === '/live')) {
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-cache, no-store, must-revalidate'
    });
    res.end(getDashboardHtml());
    return;
  }

  if (req.method === 'GET' && (pathname === '/api/analytics' || pathname === '/analytics')) {
    res.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-cache, no-store, must-revalidate'
    });
    res.end(JSON.stringify(computeAnalytics(), null, 2));
    return;
  }

  if (req.method === 'POST' && (pathname === '/api/telemetry' || pathname === '/telemetry')) {
    let bodyStr = '';
    req.on('data', chunk => { bodyStr += chunk; });
    req.on('end', () => {
      try {
        const data = JSON.parse(bodyStr);
        const userId = String(data.userId || '').trim();
        if (!userId) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: false, error: 'missing_user_id' }));
          return;
        }

        const now = Date.now();
        telemetryStats.totalUsersSeen.add(userId);

        const isPlaying = Boolean(data.isPlaying);
        const track = data.track || {};
        const totalSeconds = Number(data.totalSecondsListened) || 0;
        const appVersion = String(data.appVersion || '1.0');

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

        if (isPlaying && track.title && track.artist && !track.isPodcast) {
          const trackKey = `${track.title} - ${track.artist}`.trim();
          const existingT = telemetryStats.topTracks.get(trackKey) || { title: track.title, artist: track.artist, count: 0 };
          if (data.event === 'play' || data.event === 'track_change') {
            existingT.count += 1;
            telemetryStats.topTracks.set(trackKey, existingT);

            const artistKey = track.artist.trim();
            const aCount = (telemetryStats.topArtists.get(artistKey) || 0) + 1;
            telemetryStats.topArtists.set(artistKey, aCount);
          }
        }

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, activeUsers: getActiveUsersCount() }));
      } catch (err) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, error: err.message }));
      }
    });
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not found');
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`SpotUI Live Dashboard & Telemetry Server listening on http://localhost:${PORT}/dashboard`);
});
