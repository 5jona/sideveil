// Zero-framework SideVeil plugin using origin-pinned WebMessageListener + JSON-RPC 2.0.
const NOTE_FILE = 'note.txt';
const FALLBACK_STORAGE_KEY = 'sideveil:example.vanilla-hello:note';

const statusBadge = document.getElementById('status-badge');
const capsLine = document.getElementById('caps-line');
const noteInput = document.getElementById('note-input');
const saveBtn = document.getElementById('save-btn');
const toastBtn = document.getElementById('toast-btn');
const dismissBtn = document.getElementById('dismiss-btn');

const host = window.__POCHITA_HOST__;
const hasNativeHost = Boolean(host && typeof host.postMessage === 'function');
const pending = new Map();
let reqSeq = 0;

function applyThemeTokens(cssTokens) {
  if (!cssTokens || typeof cssTokens !== 'object') return;
  const rootStyle = document.documentElement.style;
  for (const [key, value] of Object.entries(cssTokens)) {
    if (key.startsWith('--') && typeof value === 'string' && value.trim()) {
      rootStyle.setProperty(key, value.trim());
    }
  }
}

function handleMessage(raw) {
  let msg = raw;
  if (typeof raw === 'string') {
    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }
  }
  if (!msg || typeof msg !== 'object') return;

  if (typeof msg.id === 'string' && pending.has(msg.id)) {
    const entry = pending.get(msg.id);
    pending.delete(msg.id);
    clearTimeout(entry.timer);
    if (msg.error) {
      entry.reject(new Error(`${msg.error.message} (code ${msg.error.code})`));
    } else {
      entry.resolve(msg.result);
    }
    return;
  }

  if (msg.event === 'host.ready' && msg.payload) {
    const { protocolVersion, apiVersion, grantedCapabilities = [] } = msg.payload;
    statusBadge.textContent = `已连接 v${protocolVersion}.${apiVersion}`;
    capsLine.textContent = `授权能力：${grantedCapabilities.join(', ') || '无'}`;
    return;
  }

  if (msg.event === 'theme.paletteUpdated' && msg.payload?.cssTokens) {
    applyThemeTokens(msg.payload.cssTokens);
  }
}

function invoke(method, params = {}, timeoutMs = 5000) {
  if (!hasNativeHost) {
    return Promise.reject(new Error('Native host unavailable'));
  }
  const id = `vanilla_${++reqSeq}`;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`RPC ${method} timed out`));
    }, timeoutMs);
    pending.set(id, { resolve, reject, timer });
    host.postMessage(JSON.stringify({ jsonrpc: '2.0', id, method, params }));
  });
}

async function loadNote() {
  if (!hasNativeHost) {
    statusBadge.textContent = '浏览器预览';
    capsLine.textContent = '授权能力：本地预览模式 (localStorage)';
    noteInput.value = localStorage.getItem(FALLBACK_STORAGE_KEY) ?? '';
    return;
  }
  try {
    const res = await invoke('fs.read', { path: NOTE_FILE });
    if (typeof res?.content === 'string') {
      noteInput.value = res.content;
    }
  } catch (err) {
    capsLine.textContent = `读取便签失败：${err.message}`;
  }
}

async function saveNote() {
  const content = noteInput.value;
  if (!hasNativeHost) {
    localStorage.setItem(FALLBACK_STORAGE_KEY, content);
    statusBadge.textContent = '已保存 (本地)';
    return;
  }
  try {
    await invoke('fs.writeAtomic', { path: NOTE_FILE, content });
    await invoke('ui.toast', { message: 'Vanilla 便签已保存' });
  } catch (err) {
    capsLine.textContent = `保存失败：${err.message}`;
  }
}

if (hasNativeHost) {
  if (typeof host.addEventListener === 'function') {
    host.addEventListener('message', (ev) => handleMessage(ev.data));
    host.postMessage(JSON.stringify({ event: 'host.attach', channel: 'listener', protocolVersion: 1 }));
  } else {
    window.addEventListener('message', (ev) => handleMessage(ev.data));
  }
}

saveBtn.addEventListener('click', () => void saveNote());
toastBtn.addEventListener('click', () => {
  if (!hasNativeHost) {
    statusBadge.textContent = 'Toast 预览';
    return;
  }
  void invoke('ui.toast', { message: '来自零框架 Vanilla 插件的问候！' }).catch((err) => {
    capsLine.textContent = `Toast 失败：${err.message}`;
  });
});
dismissBtn.addEventListener('click', () => {
  if (hasNativeHost) {
    void invoke('container.action', { type: 'dismiss' }).catch(() => {});
  }
});
noteInput.addEventListener('focus', () => {
  if (hasNativeHost) {
    void invoke('container.action', { type: 'requestImeFocus', focused: true }).catch(() => {});
  }
});
noteInput.addEventListener('blur', () => {
  if (hasNativeHost) {
    void invoke('container.action', { type: 'requestImeFocus', focused: false }).catch(() => {});
  }
});

void loadNote();
