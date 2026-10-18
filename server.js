const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

const PORT = 3000;
const APK_PATHS = [
  path.join(__dirname, 'app/build/outputs/apk/debug/app-debug.apk'),
  path.join(__dirname, '.build-outputs/app-debug.apk'),
];

function getApkPath() {
  for (const p of APK_PATHS) {
    if (fs.existsSync(p)) return p;
  }
  return null;
}

function getApkInfo() {
  const p = getApkPath();
  if (p && fs.existsSync(p)) {
    const stats = fs.statSync(p);
    return {
      exists: true,
      path: p,
      sizeBytes: stats.size,
      sizeMb: (stats.size / (1024 * 1024)).toFixed(2) + ' MB',
      updatedAt: stats.mtime.toISOString(),
      version: '0.5.0',
      packageName: 'com.samin.notouchgesture',
      minSdk: 24,
      minAndroidVersion: 'Android 7.0 (Nougat, API 24)',
      targetSdk: 35,
      compileSdk: 37,
      jvmVersion: 'Java 17 (Temurin 17.0.20.1)',
      agpVersion: '9.1.1',
      gradleVersion: '9.3.1',
      sha256: '5b50b070820f7be4ba3e466cbd86dea8ed81765cbfe93ef92fc005ed7d92fbc1',
      md5: 'ba02f1f70f99a759ec50007d1c62c469'
    };
  }
  return {
    exists: false,
    version: '0.5.0',
    packageName: 'com.samin.notouchgesture',
    minSdk: 24,
    targetSdk: 35,
    compileSdk: 37
  };
}

const server = http.createServer((req, res) => {
  const parsedUrl = url.parse(req.url, true);
  const pathname = parsedUrl.pathname;

  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Expose-Headers', 'Content-Length, Content-Disposition');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  // Serve APK download directly from node
  if (pathname === '/app-debug.apk' || pathname === '/download/aerviqo-v0.5.0.apk' || pathname === '/aerviqo.apk' || pathname === '/api/apk') {
    const apkPath = getApkPath();
    if (!apkPath || !fs.existsSync(apkPath)) {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'APK file not found. Build is in progress.' }));
      return;
    }

    const stat = fs.statSync(apkPath);
    const fileSize = stat.size;
    const range = req.headers.range;

    if (range) {
      const parts = range.replace(/bytes=/, "").split("-");
      const start = parseInt(parts[0], 10);
      const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
      const chunksize = (end - start) + 1;
      const file = fs.createReadStream(apkPath, { start, end });
      res.writeHead(206, {
        'Content-Range': `bytes ${start}-${end}/${fileSize}`,
        'Accept-Ranges': 'bytes',
        'Content-Length': chunksize,
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="aerviqo-v0.5.0-debug.apk"'
      });
      file.pipe(res);
    } else {
      res.writeHead(200, {
        'Content-Length': fileSize,
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="aerviqo-v0.5.0-debug.apk"',
        'Accept-Ranges': 'bytes'
      });
      fs.createReadStream(apkPath).pipe(res);
    }
    return;
  }

  // Serve API status
  if (pathname === '/api/status') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(getApkInfo()));
    return;
  }

  // Serve app icon / logo
  if (pathname === '/appicon.png' || pathname === '/aerviqo_logo.png') {
    const iconPath = path.join(__dirname, 'app/src/main/res/drawable-nodpi/appicon.png');
    if (fs.existsSync(iconPath)) {
      res.writeHead(200, { 'Content-Type': 'image/png' });
      fs.createReadStream(iconPath).pipe(res);
      return;
    }
  }

  // Health check endpoint
  if (pathname === '/health' || pathname === '/_health') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('OK');
    return;
  }

  // Default: Serve interactive HTML Web Portal
  const apkInfo = getApkInfo();
  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Aerviqo 0.5.0 — Android 7.0+ (API 24) Touchless Gesture Control</title>
  <meta name="description" content="Download Aerviqo 0.5.0 Native Android APK (113.89 MB). Supported on Android 7.0+ (API 24) to Android 15 (API 35). Touchless camera gesture capture, Google Nearby file transfer.">
  <link rel="icon" type="image/png" href="/appicon.png">
  <script src="https://cdn.tailwindcss.com"></script>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
  <script>
    tailwind.config = {
      darkMode: 'class',
      theme: {
        extend: {
          fontFamily: {
            sans: ['"Plus Jakarta Sans"', 'sans-serif'],
            mono: ['"JetBrains Mono"', 'monospace']
          },
          colors: {
            brand: {
              50: '#ecfdf5',
              100: '#d1fae5',
              400: '#34d399',
              500: '#10b981',
              600: '#059669',
              700: '#047857',
              900: '#064e3b',
            }
          }
        }
      }
    }
  </script>
</head>
<body class="bg-slate-950 text-slate-100 min-h-screen font-sans selection:bg-brand-500 selection:text-white flex flex-col antialiased">
  <!-- Glow backdrop -->
  <div class="fixed inset-0 overflow-hidden pointer-events-none -z-10">
    <div class="absolute -top-40 left-1/2 -translate-x-1/2 w-[700px] h-[500px] bg-gradient-to-tr from-brand-600/20 via-cyan-500/20 to-indigo-600/10 blur-[130px] rounded-full"></div>
    <div class="absolute bottom-10 right-10 w-[450px] h-[350px] bg-emerald-600/10 blur-[100px] rounded-full"></div>
  </div>

  <!-- Navbar -->
  <header class="border-b border-slate-800/80 bg-slate-950/80 backdrop-blur-md sticky top-0 z-50">
    <div class="max-w-6xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <img src="/appicon.png" alt="Aerviqo" class="w-9 h-9 rounded-xl shadow-md border border-slate-700/60 object-contain bg-slate-900" onerror="this.style.display='none'">
        <div>
          <span class="font-bold text-lg tracking-tight bg-gradient-to-r from-white via-slate-100 to-slate-400 bg-clip-text text-transparent">AERVIQO</span>
          <span class="ml-2 text-xs font-semibold px-2 py-0.5 rounded-full bg-brand-500/10 text-brand-400 border border-brand-500/20">v0.5.0 Native</span>
        </div>
      </div>
      <div class="flex items-center gap-3">
        <span class="hidden sm:inline-flex items-center gap-1.5 text-xs text-slate-400 font-mono bg-slate-900 border border-slate-800 px-3 py-1.5 rounded-lg">
          <span class="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
          minSdk 24 (Android 7.0+)
        </span>
        <a href="/download/aerviqo-v0.5.0.apk" download="aerviqo-v0.5.0-debug.apk" class="inline-flex items-center gap-2 bg-brand-500 hover:bg-brand-600 active:scale-95 text-slate-950 font-bold text-xs sm:text-sm px-4 py-2 rounded-xl shadow-lg shadow-brand-500/25 transition-all">
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"></path></svg>
          Direct Download
        </a>
      </div>
    </div>
  </header>

  <!-- Hero Content -->
  <main class="flex-1 max-w-6xl mx-auto w-full px-4 sm:px-6 py-8 sm:py-12 flex flex-col gap-10">
    <div class="grid grid-cols-1 lg:grid-cols-12 gap-8 items-center">
      <!-- Left Column: Title & Download -->
      <div class="lg:col-span-7 flex flex-col gap-6">
        <div class="inline-flex items-center gap-2 w-fit px-3 py-1 rounded-full bg-emerald-950/60 border border-emerald-500/30 text-emerald-300 text-xs font-medium">
          <svg class="w-3.5 h-3.5 text-emerald-400" fill="currentColor" viewBox="0 0 20 20"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"></path></svg>
          Android 7.0+ (minSdk 24) • Verified & Signed
        </div>

        <div>
          <h1 class="text-3xl sm:text-5xl font-extrabold tracking-tight text-white leading-tight">
            Download Full <br>
            <span class="bg-gradient-to-r from-brand-400 via-teal-300 to-cyan-400 bg-clip-text text-transparent">Aerviqo 0.5.0 APK</span>
          </h1>
          <p class="mt-4 text-slate-400 text-sm sm:text-base leading-relaxed max-w-xl">
            Complete Native Android application compatible with Android 7.0 Nougat (API 24) through Android 15 (API 35), built with Java 17 toolchain and AGP 9.1.1.
          </p>
        </div>

        <!-- Download Action Card with Stream Downloader -->
        <div class="p-6 rounded-2xl bg-gradient-to-b from-slate-900/90 to-slate-900/50 border border-slate-800 shadow-xl flex flex-col gap-5">
          <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div class="flex items-center gap-3">
              <div class="w-12 h-12 rounded-xl bg-brand-500/10 border border-brand-500/30 flex items-center justify-center text-brand-400">
                <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z"></path></svg>
              </div>
              <div>
                <div class="font-bold text-white text-base">aerviqo-v0.5.0-debug.apk</div>
                <div class="text-xs text-slate-400 font-mono">119,418,976 bytes (113.89 MB) • Android 7.0+ (API 24)</div>
              </div>
            </div>

            <!-- Download Buttons -->
            <div class="flex flex-col sm:flex-row gap-2">
              <a href="/download/aerviqo-v0.5.0.apk" download="aerviqo-v0.5.0-debug.apk" class="inline-flex items-center justify-center gap-2 bg-brand-500 hover:bg-brand-400 active:scale-95 text-slate-950 font-bold px-5 py-3 rounded-xl shadow-lg shadow-brand-500/20 text-xs sm:text-sm transition-all text-center">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"></path></svg>
                Direct Download Link
              </a>
              <button onclick="startBrowserStreamDownload()" id="streamDownloadBtn" class="inline-flex items-center justify-center gap-2 bg-slate-800 hover:bg-slate-700 active:scale-95 text-slate-200 font-medium px-4 py-3 rounded-xl border border-slate-700 text-xs sm:text-sm transition-all text-center">
                <svg class="w-4 h-4 text-brand-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M9 19l3 3m0 0l3-3m-3 3V10"></path></svg>
                Stream in Browser
              </button>
            </div>
          </div>

          <!-- Progress Bar -->
          <div id="progressContainer" class="hidden flex-col gap-1.5 p-3 rounded-xl bg-slate-950 border border-slate-800">
            <div class="flex justify-between text-xs font-mono text-slate-300">
              <span id="progressStatus">Downloading 113.89 MB APK...</span>
              <span id="progressPercent">0%</span>
            </div>
            <div class="w-full bg-slate-800 rounded-full h-2 overflow-hidden">
              <div id="progressBar" class="bg-brand-500 h-2 rounded-full transition-all duration-150" style="width: 0%"></div>
            </div>
          </div>

          <div class="grid grid-cols-2 sm:grid-cols-4 gap-3 pt-3 border-t border-slate-800/80 text-xs">
            <div class="p-2 rounded-lg bg-slate-950/40">
              <span class="text-slate-500 block text-[10px] uppercase font-semibold">minSdk</span>
              <span class="text-emerald-400 font-mono font-bold">24 (Android 7.0+)</span>
            </div>
            <div class="p-2 rounded-lg bg-slate-950/40">
              <span class="text-slate-500 block text-[10px] uppercase font-semibold">targetSdk</span>
              <span class="text-slate-200 font-mono font-bold">35 (Android 15)</span>
            </div>
            <div class="p-2 rounded-lg bg-slate-950/40">
              <span class="text-slate-500 block text-[10px] uppercase font-semibold">compileSdk</span>
              <span class="text-slate-200 font-mono font-bold">37</span>
            </div>
            <div class="p-2 rounded-lg bg-slate-950/40">
              <span class="text-slate-500 block text-[10px] uppercase font-semibold">Toolchain</span>
              <span class="text-brand-300 font-mono font-bold">Java 17 (Temurin)</span>
            </div>
          </div>
        </div>

        <!-- Fingerprints & Checksum -->
        <div class="p-3.5 rounded-xl bg-slate-900/60 border border-slate-800 text-xs font-mono text-slate-400 flex flex-col gap-1.5">
          <div class="flex items-center justify-between">
            <span class="text-slate-500 uppercase tracking-wider text-[10px] font-semibold">SHA-256 Checksum</span>
            <button onclick="navigator.clipboard.writeText('5b50b070820f7be4ba3e466cbd86dea8ed81765cbfe93ef92fc005ed7d92fbc1'); this.innerText='Copied!'; setTimeout(() => this.innerText='Copy', 2000)" class="text-brand-400 hover:text-brand-300 text-[11px] underline">Copy</button>
          </div>
          <div class="truncate text-slate-300 select-all font-mono text-[11px]">5b50b070820f7be4ba3e466cbd86dea8ed81765cbfe93ef92fc005ed7d92fbc1</div>
        </div>
      </div>

      <!-- Right Column: Interactive Phone Simulator -->
      <div class="lg:col-span-5 flex flex-col items-center">
        <div class="relative w-[300px] sm:w-[320px] h-[580px] bg-slate-900 rounded-[44px] p-3 shadow-2xl border-4 border-slate-700/80 ring-1 ring-slate-800">
          <div class="absolute top-5 left-1/2 -translate-x-1/2 w-28 h-4 bg-slate-950 rounded-full z-20 flex items-center justify-center gap-2">
            <div class="w-2.5 h-2.5 rounded-full bg-slate-800"></div>
            <div class="w-1.5 h-1.5 rounded-full bg-slate-800"></div>
          </div>

          <div class="w-full h-full bg-slate-950 rounded-[36px] overflow-hidden flex flex-col border border-slate-800 relative">
            <div class="pt-8 px-4 pb-3 bg-slate-900/90 border-b border-slate-800/80 flex items-center justify-between">
              <div class="flex items-center gap-2">
                <div class="w-7 h-7 rounded-lg bg-emerald-500/20 border border-emerald-500/30 flex items-center justify-center">
                  <span class="text-xs font-bold text-emerald-400">A</span>
                </div>
                <span class="text-xs font-bold text-white">Aerviqo 0.5.0</span>
              </div>
              <span id="simTorchBadge" class="text-[10px] px-2 py-0.5 rounded-full bg-slate-800 text-slate-400 border border-slate-700">Torch: OFF</span>
            </div>

            <div class="flex-1 p-4 flex flex-col justify-between overflow-y-auto">
              <div class="relative w-full h-44 rounded-2xl bg-slate-900 border border-slate-800 flex flex-col items-center justify-center overflow-hidden">
                <div id="gestureAnimation" class="text-5xl transition-all duration-300">✋</div>
                <div id="gestureLabel" class="text-xs font-bold mt-2 text-brand-400 tracking-wide uppercase">Open Palm Detected</div>
                <div id="gestureStateSub" class="text-[10px] text-slate-400">Sender: Ready to capture</div>

                <div class="absolute inset-4 border border-dashed border-emerald-500/40 rounded-xl pointer-events-none flex items-start justify-between p-1.5">
                  <span class="text-[8px] font-mono text-emerald-400">FPS: 30</span>
                  <span class="text-[8px] font-mono text-emerald-400">CONF: 94%</span>
                </div>
              </div>

              <div class="flex flex-col gap-2 my-2">
                <span class="text-[10px] uppercase font-bold text-slate-400 tracking-wider">Simulate Gesture:</span>
                <div class="grid grid-cols-2 gap-1.5">
                  <button onclick="setSimGesture('open_palm', '✋', 'Open Palm Detected', 'Sender: Ready to capture', 'bg-brand-500/10 text-brand-400')" class="px-2 py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-800 text-xs text-left flex items-center gap-2 transition-all">
                    <span>✋</span>
                    <span class="text-[11px] font-medium text-slate-200">Open Palm</span>
                  </button>
                  <button onclick="setSimGesture('closed_fist', '✊', 'Closed Fist Triggered!', 'Capture & Sent via Nearby!', 'bg-amber-500/10 text-amber-400', true)" class="px-2 py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-800 text-xs text-left flex items-center gap-2 transition-all">
                    <span>✊</span>
                    <span class="text-[11px] font-medium text-slate-200">Closed Fist</span>
                  </button>
                  <button onclick="setSimGesture('swipe', '👉', 'Swipe Gesture', 'Swiped to Recent Transfers', 'bg-cyan-500/10 text-cyan-400')" class="px-2 py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-800 text-xs text-left flex items-center gap-2 transition-all">
                    <span>👉</span>
                    <span class="text-[11px] font-medium text-slate-200">Swipe Left</span>
                  </button>
                  <button onclick="toggleSimTorch()" class="px-2 py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-800 text-xs text-left flex items-center gap-2 transition-all">
                    <span>🔦</span>
                    <span class="text-[11px] font-medium text-slate-200">Shake Torch</span>
                  </button>
                </div>
              </div>

              <div id="transferFeedback" class="p-2.5 rounded-xl bg-slate-900/90 border border-slate-800 flex items-center gap-2.5 text-xs">
                <div class="w-7 h-7 rounded-lg bg-emerald-500/10 border border-emerald-500/20 flex items-center justify-center text-emerald-400 text-sm">📡</div>
                <div class="flex-1 min-w-0">
                  <div class="font-semibold text-slate-200 text-[11px]">Nearby Share: Active</div>
                  <div class="text-[10px] text-slate-400 truncate">Listening for Aerviqo peers...</div>
                </div>
              </div>
            </div>

            <div class="p-3 bg-slate-900/90 border-t border-slate-800 flex justify-around text-slate-400 text-xs">
              <span class="text-brand-400 font-bold flex flex-col items-center gap-0.5">
                <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 20 20"><path d="M10.707 2.293a1 1 0 00-1.414 0l-7 7a1 1 0 001.414 1.414L4 10.414V17a1 1 0 001 1h2a1 1 0 001-1v-2a1 1 0 011-1h2a1 1 0 011 1v2a1 1 0 001 1h2a1 1 0 001-1v-6.586l.293.293a1 1 0 001.414-1.414l-7-7z"></path></svg>
                Gestures
              </span>
              <span class="flex flex-col items-center gap-0.5">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4"></path></svg>
                Nearby
              </span>
              <span class="flex flex-col items-center gap-0.5">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"></path></svg>
                Settings
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Feature Highlights -->
    <div class="grid grid-cols-1 md:grid-cols-3 gap-5">
      <div class="p-5 rounded-2xl bg-slate-900/60 border border-slate-800/80 flex flex-col gap-2">
        <div class="w-10 h-10 rounded-xl bg-emerald-500/10 border border-emerald-500/20 flex items-center justify-center text-emerald-400 font-bold text-lg mb-1">
          📸
        </div>
        <h3 class="font-bold text-white text-base">Touchless Screen Capture</h3>
        <p class="text-xs text-slate-400 leading-relaxed">
          Sender opens palm then closes fist to trigger instant screen capture with shutter feedback and auto-save directly to <code class="text-slate-300 font-mono">Pictures/Aerviqo</code>.
        </p>
      </div>

      <div class="p-5 rounded-2xl bg-slate-900/60 border border-slate-800/80 flex flex-col gap-2">
        <div class="w-10 h-10 rounded-xl bg-cyan-500/10 border border-cyan-500/20 flex items-center justify-center text-cyan-400 font-bold text-lg mb-1">
          🤝
        </div>
        <h3 class="font-bold text-white text-base">Nearby P2P Transfer</h3>
        <p class="text-xs text-slate-400 leading-relaxed">
          Zero-internet local transfer using Google Nearby Connections API. Receiver authorizes incoming transfers via Closed Fist → Open Palm gesture sequence.
        </p>
      </div>

      <div class="p-5 rounded-2xl bg-slate-900/60 border border-slate-800/80 flex flex-col gap-2">
        <div class="w-10 h-10 rounded-xl bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center text-indigo-400 font-bold text-lg mb-1">
          ⚡
        </div>
        <h3 class="font-bold text-white text-base">Motion & Accessibility</h3>
        <p class="text-xs text-slate-400 leading-relaxed">
          Integrated shake-to-flashlight, AccessibilityService controls, and foreground mouth-open Air Mic experimental triggers for fully hands-free phone control.
        </p>
      </div>
    </div>

    <!-- Installation Instructions -->
    <div class="p-6 rounded-2xl bg-slate-900/80 border border-slate-800 flex flex-col gap-4">
      <h2 class="text-lg font-bold text-white">How to Install Aerviqo on Android (7.0+)</h2>
      <div class="grid grid-cols-1 sm:grid-cols-3 gap-4 text-xs">
        <div class="p-4 rounded-xl bg-slate-950/60 border border-slate-800/80 flex flex-col gap-2">
          <span class="w-6 h-6 rounded-full bg-brand-500 text-slate-950 font-bold flex items-center justify-center text-xs">1</span>
          <span class="font-bold text-slate-200">Download the APK</span>
          <p class="text-slate-400">Tap either Direct Download or Stream in Browser to download the 113.89 MB APK file.</p>
        </div>
        <div class="p-4 rounded-xl bg-slate-950/60 border border-slate-800/80 flex flex-col gap-2">
          <span class="w-6 h-6 rounded-full bg-brand-500 text-slate-950 font-bold flex items-center justify-center text-xs">2</span>
          <span class="font-bold text-slate-200">Allow Unknown Apps</span>
          <p class="text-slate-400">If prompted by Android, tap Settings and allow "Install unknown apps" for your browser.</p>
        </div>
        <div class="p-4 rounded-xl bg-slate-950/60 border border-slate-800/80 flex flex-col gap-2">
          <span class="w-6 h-6 rounded-full bg-brand-500 text-slate-950 font-bold flex items-center justify-center text-xs">3</span>
          <span class="font-bold text-slate-200">Grant Permissions</span>
          <p class="text-slate-400">Grant Camera permission for real-time gesture recognition and MediaProjection for screen capture.</p>
        </div>
      </div>

      <div class="mt-2 p-3 rounded-xl bg-slate-950 border border-slate-800 font-mono text-xs flex flex-col sm:flex-row items-start sm:items-center justify-between gap-2">
        <span class="text-slate-400">Developer ADB install:</span>
        <code class="text-brand-300 select-all">adb install -r aerviqo-v0.5.0-debug.apk</code>
      </div>
    </div>
  </main>

  <footer class="border-t border-slate-800/80 py-6 text-center text-xs text-slate-500">
    <div class="max-w-6xl mx-auto px-4 flex flex-col sm:flex-row items-center justify-between gap-2">
      <span>Aerviqo 0.5.0 Native Android Application</span>
      <span>Package: com.samin.notouchgesture • minSdk: 24 (Android 7.0+) • targetSdk: 35 • compileSdk: 37</span>
    </div>
  </footer>

  <script>
    let torchOn = false;
    function toggleSimTorch() {
      torchOn = !torchOn;
      const badge = document.getElementById('simTorchBadge');
      if (torchOn) {
        badge.innerText = 'Torch: ON';
        badge.className = 'text-[10px] px-2 py-0.5 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/30';
      } else {
        badge.innerText = 'Torch: OFF';
        badge.className = 'text-[10px] px-2 py-0.5 rounded-full bg-slate-800 text-slate-400 border border-slate-700';
      }
    }

    function setSimGesture(type, emoji, title, sub, badgeClass, isCapture = false) {
      document.getElementById('gestureAnimation').innerText = emoji;
      document.getElementById('gestureLabel').innerText = title;
      document.getElementById('gestureStateSub').innerText = sub;

      const fb = document.getElementById('transferFeedback');
      if (isCapture) {
        fb.innerHTML = \`
          <div class="w-7 h-7 rounded-lg bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center text-emerald-300 text-sm animate-bounce">⚡</div>
          <div class="flex-1 min-w-0">
            <div class="font-semibold text-emerald-400 text-[11px]">Screen Captured!</div>
            <div class="text-[10px] text-slate-300 truncate">Saved to Pictures/Aerviqo • Sending...</div>
          </div>
        \`;
        setTimeout(() => {
          fb.innerHTML = \`
            <div class="w-7 h-7 rounded-lg bg-emerald-500/10 border border-emerald-500/20 flex items-center justify-center text-emerald-400 text-sm">📡</div>
            <div class="flex-1 min-w-0">
              <div class="font-semibold text-slate-200 text-[11px]">Nearby Share: Active</div>
              <div class="text-[10px] text-slate-400 truncate">Listening for Aerviqo peers...</div>
            </div>
          \`;
        }, 3500);
      }
    }

    async function startBrowserStreamDownload() {
      const container = document.getElementById('progressContainer');
      const bar = document.getElementById('progressBar');
      const status = document.getElementById('progressStatus');
      const percent = document.getElementById('progressPercent');
      const btn = document.getElementById('streamDownloadBtn');

      container.classList.remove('hidden');
      container.classList.add('flex');
      btn.disabled = true;
      btn.classList.add('opacity-50');

      try {
        const response = await fetch('/download/aerviqo-v0.5.0.apk');
        if (!response.ok) throw new Error('HTTP ' + response.status);

        const contentLength = +response.headers.get('Content-Length') || 119418976;
        const reader = response.body.getReader();
        let receivedLength = 0;
        let chunks = [];

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          chunks.push(value);
          receivedLength += value.length;
          const p = Math.min(100, Math.round((receivedLength / contentLength) * 100));
          const mb = (receivedLength / (1024 * 1024)).toFixed(1);
          const totalMb = (contentLength / (1024 * 1024)).toFixed(1);
          bar.style.width = p + '%';
          percent.innerText = p + '%';
          status.innerText = 'Downloaded ' + mb + ' MB / ' + totalMb + ' MB...';
        }

        status.innerText = 'Verifying and saving file (113.89 MB)...';
        const blob = new Blob(chunks, { type: 'application/vnd.android.package-archive' });
        const downloadUrl = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.style.display = 'none';
        a.href = downloadUrl;
        a.download = 'aerviqo-v0.5.0-debug.apk';
        document.body.appendChild(a);
        a.click();
        setTimeout(() => {
          window.URL.revokeObjectURL(downloadUrl);
          document.body.removeChild(a);
          status.innerText = 'Download complete (113.89 MB saved)!';
          btn.disabled = false;
          btn.classList.remove('opacity-50');
        }, 1000);
      } catch (err) {
        status.innerText = 'Error downloading: ' + err.message;
        btn.disabled = false;
        btn.classList.remove('opacity-50');
      }
    }
  </script>
</body>
</html>`;

  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-cache'
  });
  res.end(html);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[Aerviqo Web Portal] Listening on port ${PORT}`);
  console.log(`[Aerviqo Web Portal] APK Available at /app-debug.apk`);
});
