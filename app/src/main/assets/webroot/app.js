// Vanilla JS, no build step. Talks to /files/<name> (PUT/GET/DELETE) and /api/list (JSON).
// Kept in one file for now per the "no mandatory build system" requirement; split into
// api.js/ui.js/transfers.js once this grows past a single screen's worth of logic.

const MAX_CONCURRENT_UPLOADS = 3;

const fileInput = document.getElementById('fileInput');
const uploadButton = document.getElementById('uploadButton');
const transferArea = document.getElementById('transferArea');
const transferList = document.getElementById('transferList');
const fileListEl = document.getElementById('fileList');
const emptyState = document.getElementById('emptyState');
const refreshButton = document.getElementById('refreshButton');
const toastEl = document.getElementById('toast');

let selectedFiles = [];

fileInput.addEventListener('change', () => {
  selectedFiles = Array.from(fileInput.files);
  uploadButton.disabled = selectedFiles.length === 0;
  uploadButton.textContent = selectedFiles.length > 0
    ? `Upload ${selectedFiles.length} file${selectedFiles.length > 1 ? 's' : ''}`
    : 'Upload';
});

uploadButton.addEventListener('click', () => {
  if (selectedFiles.length === 0) return;
  uploadAll(selectedFiles);
  selectedFiles = [];
  fileInput.value = '';
  uploadButton.disabled = true;
  uploadButton.textContent = 'Upload';
});

refreshButton.addEventListener('click', loadFileList);

/** Uploads files with bounded concurrency via XMLHttpRequest (fetch() doesn't expose
 *  reliable upload progress across browsers, notably iOS Safari, so XHR is used here
 *  deliberately). Each file becomes a real HTTP PUT to /files/<name> -- no base64,
 *  no wrapping the bytes in JSON. */
function uploadAll(files) {
  transferArea.hidden = false;
  const queue = [...files];
  let active = 0;

  function next() {
    if (queue.length === 0) return;
    if (active >= MAX_CONCURRENT_UPLOADS) return;
    const file = queue.shift();
    active++;
    uploadOne(file, () => {
      active--;
      next();
      if (active === 0 && queue.length === 0) loadFileList();
    });
    next(); // fill remaining concurrency slots
  }
  next();
}

function uploadOne(file, onDone) {
  const row = createTransferRow(file.name, file.size);
  const xhr = new XMLHttpRequest();
  const url = '/files/' + encodeURIComponent(file.name);

  let lastLoaded = 0;
  let lastTime = performance.now();

  xhr.upload.addEventListener('progress', (e) => {
    if (!e.lengthComputable) return;
    const now = performance.now();
    const dt = (now - lastTime) / 1000;
    const db = e.loaded - lastLoaded;
    const speed = dt > 0 ? db / dt : 0;
    updateTransferRow(row, e.loaded, e.total, speed);
    lastLoaded = e.loaded;
    lastTime = now;
  });

  xhr.addEventListener('load', () => {
    if (xhr.status >= 200 && xhr.status < 300) {
      finishTransferRow(row, true);
    } else {
      finishTransferRow(row, false, `HTTP ${xhr.status}`);
    }
    onDone();
  });

  xhr.addEventListener('error', () => {
    finishTransferRow(row, false, 'Network error');
    onDone();
  });

  xhr.open('PUT', url, true);
  xhr.setRequestHeader('Content-Type', 'application/octet-stream');
  xhr.send(file);
}

function createTransferRow(name, size) {
  const item = document.createElement('div');
  item.className = 'py-3';
  item.innerHTML = `
    <div class="flex items-center justify-between gap-3 text-sm"><span>${escapeHtml(name)}</span><span class="pct">0%</span></div>
    <div class="mt-2 h-1 overflow-hidden rounded-full bg-white/7"><div class="progress-fill h-full w-0 rounded-full bg-accent transition-[width]"></div></div>
    <div class="transfer-meta mt-1 text-xs text-zinc-500">0 / ${formatBytes(size)} &middot; 0 MB/s</div>
  `;
  transferList.prepend(item);
  return item;
}

function updateTransferRow(row, loaded, total, speed) {
  const pct = total > 0 ? Math.round((loaded / total) * 100) : 0;
  row.querySelector('.pct').textContent = pct + '%';
  row.querySelector('.progress-fill').style.width = pct + '%';
  row.querySelector('.transfer-meta').textContent =
    `${formatBytes(loaded)} / ${formatBytes(total)} \u00b7 ${formatBytes(speed)}/s`;
}

function finishTransferRow(row, success, errorMsg) {
  row.querySelector('.progress-fill').style.width = '100%';
  row.querySelector('.progress-fill').classList.toggle('bg-emerald-400', success); row.querySelector('.progress-fill').classList.toggle('bg-red-400', !success);
  row.querySelector('.pct').textContent = success ? 'Done' : (errorMsg || 'Failed');
}

async function loadFileList() {
  try {
    const res = await fetch('/api/list?path=/');
    const entries = await res.json();
    renderFileList(entries);
  } catch (e) {
    showToast('Could not load file list');
  }
}

function renderFileList(entries) {
  fileListEl.innerHTML = '';
  emptyState.hidden = entries.length > 0;
  for (const entry of entries) {
    const row = document.createElement('div');
    row.className = 'group flex min-h-16 cursor-pointer items-center gap-3 border-b border-white/5 px-4 py-2.5 transition last:border-0 hover:bg-white/[.035] active:bg-white/[.06]';
    const icon = entry.isDirectory ? '\uD83D\uDCC1' : iconFor(entry.name);
    row.innerHTML = `
      <span class="file-icon grid size-10 shrink-0 place-items-center rounded-xl bg-white/6 text-lg">${icon}</span>
      <span class="file-name min-w-0 flex-1 truncate text-sm font-medium text-zinc-200">${escapeHtml(entry.name)}</span>
      <span class="file-meta shrink-0 text-xs text-zinc-600">${entry.isDirectory ? 'Folder' : formatBytes(entry.size)}</span>
      <svg class="size-4 shrink-0 text-zinc-700 transition group-hover:text-zinc-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>
    `;
    if (!entry.isDirectory) {
      row.addEventListener('click', () => {
        window.location.href = '/files/' + encodeURIComponent(entry.name);
      });
    }
    fileListEl.appendChild(row);
  }
}

function iconFor(name) {
  const ext = name.split('.').pop().toLowerCase();
  if (['mp4', 'mov', 'mkv', 'webm', 'avi'].includes(ext)) return '\uD83C\uDFAC';
  if (['jpg', 'jpeg', 'png', 'gif', 'webp'].includes(ext)) return '\uD83D\uDDBC';
  if (['pdf'].includes(ext)) return '\uD83D\uDCC4';
  if (['mp3', 'wav', 'm4a'].includes(ext)) return '\uD83C\uDFB5';
  return '\uD83D\uDCC4';
}

function formatBytes(n) {
  if (n < 1024) return n.toFixed(0) + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  if (n < 1024 * 1024 * 1024) return (n / (1024 * 1024)).toFixed(1) + ' MB';
  return (n / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

function escapeHtml(s) {
  const div = document.createElement('div');
  div.textContent = s;
  return div.innerHTML;
}

let toastTimer = null;
function showToast(msg) {
  toastEl.textContent = msg;
  toastEl.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { toastEl.hidden = true; }, 2500);
}

loadFileList();
