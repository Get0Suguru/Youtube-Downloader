const statusBox = document.getElementById('status');
const urlInput = document.getElementById('url');
const genBtn = document.getElementById('genBtn');
const results = document.getElementById('results');
const videoList = document.getElementById('videoList');
const audioList = document.getElementById('audioList');
const preview = document.getElementById('preview');
const thumbImg = document.getElementById('thumbImg');
const tipEl = document.getElementById('tip');

let videoFormats = [];
let audioFormats = [];
let videoShown = 5;
let audioShown = 5;
let progressTimer = null;
let tipTimer = null;

function setStatus(message, type = 'info') {
  statusBox.textContent = message;
  statusBox.className = `status ${type}`;
}

function showResults(show) {
  results.classList.toggle('hidden', !show);
}

// Tab switching
Array.from(document.querySelectorAll('.tab')).forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.tabpane').forEach(p => p.classList.remove('active'));
    tab.classList.add('active');
    const which = tab.dataset.tab;
    document.getElementById(`tab-${which}`).classList.add('active');
  });
});

function parseHeight(res) {
  if (!res) return 0;
  const m = res.match(/x(\d+)/);
  return m ? parseInt(m[1], 10) : 0;
}

function bytesToSize(bytes) {
  if (!bytes || bytes <= 0) return '';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  const val = bytes / Math.pow(1024, i);
  return `${val.toFixed(val >= 100 ? 0 : val >= 10 ? 1 : 2)} ${units[i]}`;
}

function shortVCodec(vcodec) {
  if (!vcodec || vcodec === 'none') return '';
  const v = vcodec.toLowerCase();
  if (v.includes('av01') || v.includes('av1')) return 'AV1';
  if (v.includes('hev') || v.includes('h265') || v.includes('hevc')) return 'H.265';
  if (v.includes('h264') || v.includes('avc') || v.includes('avc1')) return 'H.264';
  if (v.includes('vp9')) return 'VP9';
  if (v.includes('vp8')) return 'VP8';
  if (v.includes('mpeg4')) return 'MPEG-4';
  return vcodec.toUpperCase();
}

function audioQualityRank(note) {
  if (!note) return 0;
  const n = String(note).toLowerCase();
  if (n.includes('high')) return 3;
  if (n.includes('medium')) return 2;
  if (n.includes('low')) return 1;
  return 0;
}

function extractVideoId(url) {
  try {
    const u = new URL(url);
    if (u.hostname.includes('youtu.be')) return u.pathname.slice(1);
    if (u.searchParams.get('v')) return u.searchParams.get('v');
    // Shorts
    if (u.pathname.includes('/shorts/')) return u.pathname.split('/shorts/')[1].split('/')[0];
  } catch (_) {}
  return '';
}

async function showThumbnailFast(url) {
  preview.classList.remove('hidden');
  tipEl.textContent = 'Fetching formats…';
  const tips = [
    'Tip: You can queue multiple downloads',
    'Tip: Choose video or audio from tabs',
    'Tip: Higher quality may take longer',
  ];
  let i = 0;
  clearInterval(tipTimer);
  tipTimer = setInterval(() => { i = (i + 1) % tips.length; tipEl.textContent = tips[i]; }, 1500);

  // Try oEmbed first
  try {
    const resp = await fetch(`https://www.youtube.com/oembed?url=${encodeURIComponent(url)}&format=json`);
    if (resp.ok) {
      const data = await resp.json();
      if (data && data.thumbnail_url) {
        thumbImg.src = data.thumbnail_url;
        return;
      }
    }
  } catch (_) {}
  // Fallback to i.ytimg based on id
  const vid = extractVideoId(url);
  if (vid) thumbImg.src = `https://i.ytimg.com/vi/${vid}/hqdefault.jpg`;
}

function stopThumbnailLoading() {
  clearInterval(tipTimer);
  tipTimer = null;
  tipEl.textContent = 'Ready';
}

function renderWithLimit(container, items, kind, shown, onLoadMore) {
  container.innerHTML = '';
  if (!items.length) {
    container.innerHTML = '<div class="empty">No formats found.</div>';
    return;
  }
  const slice = items.slice(0, shown);
  slice.forEach(f => {
    console.log(`[FE] rendering ${kind} id=${f.id} size=${f.filesize}`);
    const row = document.createElement('div');
    row.className = 'row';

    const meta = document.createElement('div');
    meta.className = 'meta';
    let title = '';
    if (kind === 'video') {
      const h = parseHeight(f.resolution);
      const resLabel = h ? `${h}p` : '';
      const format = (f.ext || '').toLowerCase();
      const vshort = shortVCodec(f.vcodec);
      const size = bytesToSize(f.filesize);
      title = [resLabel, format, vshort, size].filter(Boolean).join(' • ');
    } else {
      const abr = parseInt(f.abr || '0', 10) || 0;
      const quality = abr >= 160 ? 'High' : 'Low';
      const size = bytesToSize(f.filesize);
      title = ['Audio MP3 ' + quality, size].filter(Boolean).join(' • ');
    }
    meta.innerHTML = `
      <div class="title">${title}</div>
    `;

    const actions = document.createElement('div');
    actions.className = 'actions';
    const btn = document.createElement('button');
    // Button label with size
    let btnLabel = 'Download';
    if (kind === 'video') {
      const vSize = f.filesize || 0;
      // best audio aligned with backend policy: first in sorted audioFormats
      const bestAud = (Array.isArray(audioFormats) && audioFormats.length) ? audioFormats[0] : null;
      const aSize = bestAud && bestAud.filesize ? bestAud.filesize : 0;
      const total = (vSize || 0) + (aSize || 0);
      console.log('[FE] button size (video):', {vid:f.id, vSize, bestAudio: bestAud ? bestAud.id : null, aSize, total});
      const labelSize = bytesToSize(total || vSize);
      if (labelSize) btnLabel = `Download (${labelSize})`;
    } else {
      const labelSize = bytesToSize(f.filesize);
      console.log('[FE] button size (audio):', {aud:f.id, size:f.filesize, label:labelSize});
      if (labelSize) btnLabel = `Download (${labelSize})`;
    }
    btn.textContent = btnLabel;
    if (kind === 'video') {
      btn.addEventListener('click', () => downloadVideoWithBestAudio(urlInput.value.trim(), f.id));
    } else {
      btn.addEventListener('click', () => downloadAudioMp3(urlInput.value.trim(), f.id));
    }
    actions.appendChild(btn);

    row.appendChild(meta);
    row.appendChild(actions);
    container.appendChild(row);
  });

  if (items.length > shown) {
    const more = document.createElement('button');
    more.className = 'load-more';
    more.textContent = `Load more (${items.length - shown} more)`;
    more.addEventListener('click', onLoadMore);
    container.appendChild(more);
  }
}

async function generateLinks() {
  const url = urlInput.value.trim();
  if (!url) {
    setStatus('Please enter a valid YouTube URL', 'error');
    return;
  }
  genBtn.disabled = true;
  setStatus('Fetching formats...', 'info');
  showResults(false);
  videoList.innerHTML = '';
  audioList.innerHTML = '';
  videoShown = 5;
  audioShown = 5;
  showThumbnailFast(url);
  try {
    const params = new URLSearchParams({ url });
    const start = performance.now();
    const resp = await fetch(`/api/youtube/formats?${params.toString()}`);
    const end = performance.now();
    if (!resp.ok) {
      const txt = await resp.text();
      throw new Error(txt || 'Failed to fetch formats');
    }
    const formats = await resp.json();
    console.log('[FE] formats received:', formats.length, formats.slice(0, 5));
    const sizedCount = Array.isArray(formats) ? formats.filter(f => f && typeof f.filesize === 'number' && f.filesize > 0).length : 0;
    console.log('[FE] formats sized count:', sizedCount);

    // Split and sort: highest first
    videoFormats = formats
      .filter(f => f.type === 'video')
      .sort((a, b) => parseHeight(b.resolution) - parseHeight(a.resolution));
    audioFormats = formats
      .filter(f => f.type === 'audio')
      .sort((a, b) => {
        const q = audioQualityRank(b.note) - audioQualityRank(a.note);
        if (q !== 0) return q;
        const abrB = parseInt(b.abr || '0', 10) || 0;
        const abrA = parseInt(a.abr || '0', 10) || 0;
        return abrB - abrA;
      });
    console.log('[FE] video formats:', videoFormats.length, videoFormats.slice(0, 5).map(f => ({id:f.id, size:f.filesize})));
    console.log('[FE] audio formats:', audioFormats.length, audioFormats.slice(0, 5).map(f => ({id:f.id, size:f.filesize})));

    renderWithLimit(
      videoList,
      videoFormats,
      'video',
      videoShown,
      () => {
        videoShown += 5;
        renderWithLimit(videoList, videoFormats, 'video', videoShown, () => {
          videoShown += 5;
          renderWithLimit(videoList, videoFormats, 'video', videoShown, () => {});
        });
      }
    );

    renderWithLimit(
      audioList,
      audioFormats,
      'audio',
      audioShown,
      () => {
        audioShown += 5;
        renderWithLimit(audioList, audioFormats, 'audio', audioShown, () => {
          audioShown += 5;
          renderWithLimit(audioList, audioFormats, 'audio', audioShown, () => {});
        });
      }
    );

    setStatus(`Formats loaded in ${(end - start).toFixed(0)} ms.`, 'success');
    stopThumbnailLoading();
    showResults(true);
  } catch (e) {
    setStatus(`Error: ${e.message}`, 'error');
    stopThumbnailLoading();
  } finally {
    genBtn.disabled = false;
  }
}

function attachProgress() {
  // Remove any existing
  const old = document.querySelector('.progress');
  if (old && old.parentElement) old.parentElement.removeChild(old);
  const bar = document.createElement('div');
  bar.className = 'progress';
  const fill = document.createElement('div');
  bar.appendChild(fill);
  statusBox.after(bar);
  let pct = 0;
  progressTimer = setInterval(() => {
    pct = Math.min(95, pct + 1);
    fill.style.width = pct + '%';
  }, 300);
  return { bar, fill };
}

function detachProgress(finalText) {
  if (progressTimer) {
    clearInterval(progressTimer);
    progressTimer = null;
  }
  const bar = document.querySelector('.progress');
  if (bar) {
    const fill = bar.firstElementChild;
    if (fill) fill.style.width = '100%';
    setTimeout(() => bar.remove(), 500);
  }
  if (finalText) setStatus(finalText, 'success');
}

async function downloadVideoWithBestAudio(url, videoFormatId) {
  if (!url) return setStatus('Missing URL', 'error');
  setStatus('Downloading video...', 'info');
  attachProgress();
  try {
    const params = new URLSearchParams({ url, videoFormatId });
    const resp = await fetch(`/api/youtube/download/video?${params.toString()}`);
    const txt = await resp.text();
    if (!resp.ok) throw new Error(txt || 'Download failed');
    detachProgress(txt || 'Download completed');
  } catch (e) {
    detachProgress();
    setStatus(`Error: ${e.message}`, 'error');
  }
}

async function downloadAudioMp3(url, audioFormatId) {
  if (!url) return setStatus('Missing URL', 'error');
  setStatus('Downloading audio...', 'info');
  attachProgress();
  try {
    const params = new URLSearchParams({ url, audioFormatId });
    const resp = await fetch(`/api/youtube/download/audio?${params.toString()}`);
    const txt = await resp.text();
    if (!resp.ok) throw new Error(txt || 'Audio download failed');
    detachProgress(txt || 'Audio download completed');
  } catch (e) {
    detachProgress();
    setStatus(`Error: ${e.message}`, 'error');
  }
}

// Bind
genBtn.addEventListener('click', generateLinks);
