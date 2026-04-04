// --- State ---
let accounts = [];
let refreshTimer = null;
/** Cache-bust timestamps for mc-heads avatars after a successful skin change (account id → ms). */
const skinHeadBust = {};
/** Data URL of extracted 8×8 face from last applied skin — optimistic until Mojang head matches or reconcile ends. */
const skinHeadLocal = {};
/** account id → interval id for post–skin-load reconcile (Mojang session vs local assumption). */
const skinHeadReconcileTimers = {};
/** After reload, run reconcile once for accounts that have a persisted assumed head (not every poll). */
let skinHeadReconcileBootstrapped = false;

const SKIN_HEAD_BUST_STORAGE_KEY = 'mcapSkinHeadBust';
const SKIN_HEAD_LOCAL_STORAGE_KEY = 'mcapSkinHeadLocal';

const HEAD_RECONCILE_INTERVAL_MS = 1200;
const HEAD_RECONCILE_MAX_ATTEMPTS = 50;

function stopHeadReconcile(accountId) {
    const t = skinHeadReconcileTimers[accountId];
    if (t != null) {
        clearInterval(t);
        delete skinHeadReconcileTimers[accountId];
    }
}

/**
 * After a successful skin upload, keep showing the assumed head until the server-rendered head
 * matches (Mojang caught up), or we time out and adopt the server PNG as canonical.
 */
function startHeadReconcile(accountId) {
    stopHeadReconcile(accountId);
    const optimistic = skinHeadLocal[accountId];
    if (optimistic == null) return;

    let attempts = 0;
    const timer = setInterval(async () => {
        attempts += 1;
        try {
            const res = await fetch(`/api/accounts/${accountId}/head?size=28&t=${Date.now()}`, {
                cache: 'no-store',
            });
            if (!res.ok) {
                if (attempts >= HEAD_RECONCILE_MAX_ATTEMPTS) stopHeadReconcile(accountId);
                return;
            }
            const blob = await res.blob();
            const serverDataUrl = await blobToDataUrl(blob);
            const match = await headDataUrlsRoughlyEqual(optimistic, serverDataUrl);
            if (match) {
                stopHeadReconcile(accountId);
                return;
            }
            if (attempts >= HEAD_RECONCILE_MAX_ATTEMPTS) {
                skinHeadLocal[accountId] = serverDataUrl;
                persistSkinHeadLocal();
                refreshPlayerHeadUI();
                stopHeadReconcile(accountId);
            }
        } catch {
            if (attempts >= HEAD_RECONCILE_MAX_ATTEMPTS) stopHeadReconcile(accountId);
        }
    }, HEAD_RECONCILE_INTERVAL_MS);
    skinHeadReconcileTimers[accountId] = timer;
}

function blobToDataUrl(blob) {
    return new Promise((resolve, reject) => {
        const fr = new FileReader();
        fr.onload = () => resolve(fr.result);
        fr.onerror = () => reject(fr.error);
        fr.readAsDataURL(blob);
    });
}

/** True if both PNG heads are the same face (allows tiny encode/rounding differences). */
async function headDataUrlsRoughlyEqual(a, b) {
    let bmpA;
    let bmpB;
    try {
        bmpA = await loadDataUrlAsBitmap(a);
        bmpB = await loadDataUrlAsBitmap(b);
    } catch {
        return false;
    }
    try {
        const w = 28;
        const h = 28;
        const c1 = document.createElement('canvas');
        const c2 = document.createElement('canvas');
        c1.width = c2.width = w;
        c1.height = c2.height = h;
        const x1 = c1.getContext('2d');
        const x2 = c2.getContext('2d');
        x1.drawImage(bmpA, 0, 0, w, h);
        x2.drawImage(bmpB, 0, 0, w, h);
        const d1 = x1.getImageData(0, 0, w, h).data;
        const d2 = x2.getImageData(0, 0, w, h).data;
        let diff = 0;
        const len = d1.length;
        for (let i = 0; i < len; i += 4) {
            const da =
                Math.abs(d1[i] - d2[i]) +
                Math.abs(d1[i + 1] - d2[i + 1]) +
                Math.abs(d1[i + 2] - d2[i + 2]) +
                Math.abs(d1[i + 3] - d2[i + 3]);
            if (da > 12) diff += 1;
        }
        const px = w * h;
        return diff / px < 0.02;
    } finally {
        try {
            bmpA?.close?.();
            bmpB?.close?.();
        } catch {
            /* ignore */
        }
    }
}

function loadDataUrlAsBitmap(dataUrl) {
    return new Promise((resolve, reject) => {
        const img = new Image();
        img.onload = () => {
            createImageBitmap(img).then(resolve, reject);
        };
        img.onerror = () => reject(new Error('bad image'));
        img.src = dataUrl;
    });
}

function refreshPlayerHeadUI() {
    renderAccounts();
    const exportModal = document.getElementById('exportModal');
    if (exportModal && exportModal.classList.contains('active')) {
        renderExportList();
    }
}

function loadSkinHeadBustFromStorage() {
    try {
        const raw = localStorage.getItem(SKIN_HEAD_BUST_STORAGE_KEY);
        if (!raw) return;
        const o = JSON.parse(raw);
        if (o && typeof o === 'object') {
            Object.assign(skinHeadBust, o);
        }
    } catch {
        /* ignore */
    }
}

function persistSkinHeadBust() {
    try {
        localStorage.setItem(SKIN_HEAD_BUST_STORAGE_KEY, JSON.stringify(skinHeadBust));
    } catch {
        /* ignore */
    }
}

function loadSkinHeadLocalFromStorage() {
    try {
        const raw = localStorage.getItem(SKIN_HEAD_LOCAL_STORAGE_KEY);
        if (!raw) return;
        const o = JSON.parse(raw);
        if (o && typeof o === 'object') {
            Object.assign(skinHeadLocal, o);
        }
    } catch {
        /* ignore */
    }
}

function persistSkinHeadLocal() {
    try {
        localStorage.setItem(SKIN_HEAD_LOCAL_STORAGE_KEY, JSON.stringify(skinHeadLocal));
    } catch {
        /* ignore */
    }
}

/** Drop cached head entries for account ids that no longer exist (e.g. DB reset). */
function pruneSkinHeadCachesForAccounts(validIds) {
    const idSet = new Set(validIds);
    let changed = false;
    for (const k of Object.keys(skinHeadLocal)) {
        const id = Number(k);
        if (!idSet.has(id)) {
            delete skinHeadLocal[id];
            changed = true;
        }
    }
    for (const k of Object.keys(skinHeadBust)) {
        const id = Number(k);
        if (!idSet.has(id)) {
            delete skinHeadBust[id];
            changed = true;
        }
    }
    if (changed) {
        persistSkinHeadLocal();
        persistSkinHeadBust();
    }
}

// --- Init ---
document.addEventListener('DOMContentLoaded', () => {
    loadSkinHeadBustFromStorage();
    loadSkinHeadLocalFromStorage();
    loadAccounts();
    refreshTimer = setInterval(() => {
        loadAccounts();
    }, 10000);

    document.addEventListener('keydown', (e) => {
        if (e.key !== 'Escape') return;
        const skinModal = document.getElementById('skinModal');
        if (skinModal.classList.contains('active')) closeSkinModal();
    });

    const skinUrlInput = document.getElementById('skinUrlInput');
    const skinFileInput = document.getElementById('skinFileInput');
    const skinUsernameInput = document.getElementById('skinUsernameInput');
    if (skinUrlInput && skinFileInput && skinUsernameInput) {
        [skinUrlInput, skinFileInput, skinUsernameInput].forEach((el) => {
            el.addEventListener('input', syncSkinModalOptions);
            el.addEventListener('change', syncSkinModalOptions);
        });
    }
});

// --- API ---
async function api(path, options = {}) {
    const res = await fetch(path, options);
    return res.json();
}

async function loadAccounts() {
    try {
        accounts = await api('/api/accounts');
        document.getElementById('accountCount').textContent = accounts.length;
        pruneSkinHeadCachesForAccounts(accounts.map((a) => a.id));
        renderAccounts();
        if (!skinHeadReconcileBootstrapped) {
            skinHeadReconcileBootstrapped = true;
            for (const a of accounts) {
                if (skinHeadLocal[a.id] != null) {
                    startHeadReconcile(a.id);
                }
            }
        }
    } catch (e) {
        console.error('Failed to load accounts', e);
    }
}

// --- Rendering ---
function renderAccounts() {
    const tbody = document.getElementById('accountBody');
    if (accounts.length === 0) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="4">No accounts yet. Click "Add Account" to get started.</td></tr>';
        return;
    }
    const headTick = Math.floor(Date.now() / 10000);
    tbody.innerHTML = accounts.map(a => {
        const local = skinHeadLocal[a.id];
        const bust = skinHeadBust[a.id];
        const headUrl =
            local != null
                ? local
                : bust != null
                  ? `/api/accounts/${a.id}/head?size=28&t=${bust}`
                  : `/api/accounts/${a.id}/head?size=28&t=${headTick}`;
        const expiry = formatExpiry(a.tokenExpiry);
        return `<tr>
            <td>
                <div class="player-cell">
                    <img class="player-head" src="${headUrl}" alt="${a.username}" onerror="this.style.display='none'">
                    <span>${escHtml(a.username)}</span>
                </div>
            </td>
            <td class="uuid-cell">${escHtml(a.uuid)}</td>
            <td><span class="expiry-text ${isExpired(a.tokenExpiry) ? 'expired' : ''}">${expiry}</span></td>
            <td>
                <div class="actions">
                    ${a.accessToken ? `<button type="button" class="btn btn-sm btn-secondary btn-icon" aria-label="Skin" title="Skin" onclick="openSkinModal(${a.id})"><img class="btn-icon-img" src="/skin.svg" alt=""></button><button class="btn btn-sm btn-secondary" onclick="copySession(${a.id})">Copy Session</button>` : ''}
                    ${a.refreshToken ? `<button class="btn btn-sm btn-secondary" onclick="copyRefresh(${a.id})">Copy Refresh</button>` : ''}
                    <button class="btn btn-sm btn-secondary" onclick="refreshToken(${a.id})">Refresh</button>
                    <button class="btn btn-sm btn-danger" onclick="deleteAccount(${a.id})">Delete</button>
                </div>
            </td>
        </tr>`;
    }).join('');
}

function formatExpiry(ms) {
    if (!ms || ms === 0) return 'N/A';
    const now = Date.now();
    if (ms < now) return 'Expired';
    const diff = ms - now;
    const mins = Math.floor(diff / 60000);
    if (mins < 60) return `${mins}m remaining`;
    const hours = Math.floor(mins / 60);
    return `${hours}h ${mins % 60}m remaining`;
}

function isExpired(ms) {
    return ms && ms > 0 && ms < Date.now();
}

function escHtml(str) {
    const div = document.createElement('div');
    div.textContent = str || '';
    return div.innerHTML;
}

// --- Actions ---
async function copySession(id) {
    const account = accounts.find(a => a.id === id);
    if (!account || !account.accessToken) return;
    await copyToClipboard(account.accessToken);
    showToast('Session token copied');
}

async function copyRefresh(id) {
    const account = accounts.find(a => a.id === id);
    if (!account || !account.refreshToken) return;
    await copyToClipboard(account.refreshToken);
    showToast('Refresh token copied');
}

async function copyToClipboard(text) {
    try {
        await navigator.clipboard.writeText(text);
    } catch {
        const ta = document.createElement('textarea');
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
    }
}

async function refreshToken(id) {
    showToast('Refreshing token...');
    try {
        const result = await api(`/api/accounts/${id}/refresh`, { method: 'POST' });
        if (result.error) {
            showToast(result.error);
        } else {
            showToast('Token refreshed');
        }
        loadAccounts();
    } catch (e) {
        showToast('Refresh failed');
    }
}

async function deleteAccount(id) {
    if (!confirm('Remove this account from the pool?')) return;
    try {
        await api(`/api/accounts/${id}`, { method: 'DELETE' });
        stopHeadReconcile(id);
        delete skinHeadLocal[id];
        delete skinHeadBust[id];
        persistSkinHeadLocal();
        persistSkinHeadBust();
        showToast('Account removed');
        loadAccounts();
    } catch (e) {
        showToast('Delete failed');
    }
}

// --- Login Modal ---
function openLoginModal() {
    document.getElementById('loginModal').classList.add('active');
    resetLoginModal();
}

function closeLoginModal() {
    document.getElementById('loginModal').classList.remove('active');
    resetLoginModal();
}

function resetLoginModal() {
    document.getElementById('loginStart').classList.remove('hidden');
    document.getElementById('loginCode').classList.add('hidden');
    document.getElementById('loginSuccess').classList.add('hidden');
    document.getElementById('loginError').classList.add('hidden');
}

function startLogin() {
    document.getElementById('loginStart').classList.add('hidden');
    document.getElementById('loginCode').classList.remove('hidden');

    const evtSource = new EventSource('/api/accounts/login');

    evtSource.addEventListener('device_code', (e) => {
        const data = JSON.parse(e.data);
        document.getElementById('deviceCode').textContent = data.userCode;
        const link = document.getElementById('verificationLink');
        link.href = data.verificationUri;
        link.textContent = 'Open Link';
    });

    evtSource.addEventListener('success', (e) => {
        const data = JSON.parse(e.data);
        document.getElementById('loginCode').classList.add('hidden');
        document.getElementById('loginSuccess').classList.remove('hidden');
        document.getElementById('loginUsername').textContent = data.username;
        evtSource.close();
        loadAccounts();
        setTimeout(closeLoginModal, 2000);
    });

    evtSource.addEventListener('error', (e) => {
        document.getElementById('loginCode').classList.add('hidden');
        document.getElementById('loginError').classList.remove('hidden');
        if (e.data) {
            document.getElementById('loginErrorMsg').textContent = e.data;
        }
        evtSource.close();
    });

    evtSource.addEventListener('status', () => {});
}

function copyLoginLink() {
    const link = document.getElementById('verificationLink').href;
    if (!link || link === '#') return;
    copyToClipboard(link);
    showToast('Link copied — paste in incognito window');
}

// --- Export Modal ---
function openExportModal() {
    document.getElementById('exportModal').classList.add('active');
    renderExportList();
}

function closeExportModal() {
    document.getElementById('exportModal').classList.remove('active');
}

// --- Skin Modal ---
const SKIN_FILE_CHOOSE_LABEL = 'Choose File';
const SKIN_FILE_NAME_MAX_LEN = 40;

let skinModalAccountId = null;

function truncateSkinFileName(name) {
    if (name.length <= SKIN_FILE_NAME_MAX_LEN) return name;
    return name.slice(0, SKIN_FILE_NAME_MAX_LEN - 1) + '\u2026';
}

/** Official UMD bundle (self-hosted) — avoids esm.sh cold-resolve of Three.js + skinview3d. */
const SKINVIEW3D_SCRIPT = '/vendor/skinview3d.bundle.js';

let skinView3dLoadPromise = null;
let skinViewerInstance = null;
let skinPreviewObjectUrl = null;

function loadSkinView3d() {
    if (typeof window.skinview3d !== 'undefined' && window.skinview3d.SkinViewer) {
        return Promise.resolve(window.skinview3d.SkinViewer);
    }
    if (skinView3dLoadPromise) return skinView3dLoadPromise;
    skinView3dLoadPromise = new Promise((resolve, reject) => {
        const existing = document.querySelector('script[data-skinview3d]');
        if (existing) {
            const done = () => {
                if (window.skinview3d && window.skinview3d.SkinViewer) {
                    resolve(window.skinview3d.SkinViewer);
                } else {
                    reject(new Error('Skin viewer did not initialize'));
                }
            };
            if (window.skinview3d && window.skinview3d.SkinViewer) {
                done();
                return;
            }
            existing.addEventListener('load', done);
            existing.addEventListener('error', () => reject(new Error('Failed to load skin viewer')));
            return;
        }
        const s = document.createElement('script');
        s.src = SKINVIEW3D_SCRIPT;
        s.async = true;
        s.dataset.skinview3d = '1';
        s.onload = () => {
            if (window.skinview3d && window.skinview3d.SkinViewer) {
                resolve(window.skinview3d.SkinViewer);
            } else {
                reject(new Error('Skin viewer did not initialize'));
            }
        };
        s.onerror = () => reject(new Error('Failed to load skin viewer script'));
        document.head.appendChild(s);
    });
    return skinView3dLoadPromise;
}

function disposeSkinViewer() {
    if (skinViewerInstance) {
        try {
            if (typeof skinViewerInstance.dispose === 'function') {
                skinViewerInstance.dispose();
            }
        } catch (e) {
            /* ignore */
        }
        skinViewerInstance = null;
    }
    if (skinPreviewObjectUrl) {
        URL.revokeObjectURL(skinPreviewObjectUrl);
        skinPreviewObjectUrl = null;
    }
}

function hideSkinPreviewPanel() {
    const sec = document.getElementById('skinPreviewSection');
    if (sec) sec.hidden = true;
    disposeSkinViewer();
}

function openSkinModal(accountId) {
    skinModalAccountId = accountId;
    resetSkinModalFields();
    document.getElementById('skinModal').classList.add('active');
}

function closeSkinModal() {
    document.getElementById('skinModal').classList.remove('active');
    skinModalAccountId = null;
    hideSkinPreviewPanel();
}

function resetSkinModalFields() {
    const urlEl = document.getElementById('skinUrlInput');
    const fileEl = document.getElementById('skinFileInput');
    const userEl = document.getElementById('skinUsernameInput');
    if (urlEl) urlEl.value = '';
    if (fileEl) fileEl.value = '';
    if (userEl) userEl.value = '';
    const varEl = document.getElementById('skinVariantSelect');
    if (varEl) varEl.value = 'classic';
    syncSkinModalOptions();
    hideSkinPreviewPanel();
}

function openSkinFilePicker() {
    const fileEl = document.getElementById('skinFileInput');
    if (fileEl && !fileEl.disabled) {
        fileEl.click();
    }
}

function clearSkinFile() {
    const fileEl = document.getElementById('skinFileInput');
    const chooseBtn = document.getElementById('skinFileChooseBtn');
    if (fileEl) {
        fileEl.value = '';
        syncSkinModalOptions();
        if (chooseBtn && !chooseBtn.disabled) {
            chooseBtn.focus();
        }
    }
}

function syncSkinModalOptions() {
    const urlEl = document.getElementById('skinUrlInput');
    const fileEl = document.getElementById('skinFileInput');
    const userEl = document.getElementById('skinUsernameInput');
    const clearBtn = document.getElementById('skinFileClearBtn');
    if (!urlEl || !fileEl || !userEl) return;

    const urlVal = urlEl.value.trim();
    const hasFile = fileEl.files && fileEl.files.length > 0;
    const userVal = userEl.value.trim();

    let active = null;
    if (urlVal) active = 'url';
    else if (hasFile) active = 'file';
    else if (userVal) active = 'user';

    const urlDisabled = active === 'file' || active === 'user';
    const fileDisabled = active === 'url' || active === 'user';
    const userDisabled = active === 'url' || active === 'file';

    const chooseBtn = document.getElementById('skinFileChooseBtn');

    urlEl.disabled = urlDisabled;
    fileEl.disabled = fileDisabled;
    userEl.disabled = userDisabled;

    if (chooseBtn) {
        chooseBtn.disabled = fileDisabled;
        if (hasFile && fileEl.files && fileEl.files[0]) {
            const rawName = fileEl.files[0].name;
            chooseBtn.textContent = truncateSkinFileName(rawName);
            chooseBtn.title = rawName;
        } else {
            chooseBtn.textContent = SKIN_FILE_CHOOSE_LABEL;
            chooseBtn.removeAttribute('title');
        }
    }

    if (clearBtn) {
        clearBtn.hidden = !hasFile;
        clearBtn.disabled = !hasFile || fileDisabled;
    }

    document.getElementById('skinOptionUrl')?.classList.toggle('is-disabled', urlDisabled);
    document.getElementById('skinOptionFile')?.classList.toggle('is-disabled', fileDisabled);
    document.getElementById('skinOptionUsername')?.classList.toggle('is-disabled', userDisabled);
}

function getSkinModalActiveSource() {
    const urlEl = document.getElementById('skinUrlInput');
    const fileEl = document.getElementById('skinFileInput');
    const userEl = document.getElementById('skinUsernameInput');
    if (!urlEl || !fileEl || !userEl) return null;

    const urlVal = urlEl.value.trim();
    const hasFile = fileEl.files && fileEl.files.length > 0;
    const userVal = userEl.value.trim();

    if (urlVal) return { kind: 'url', url: urlVal };
    if (hasFile) return { kind: 'file', file: fileEl.files[0] };
    if (userVal) return { kind: 'user', username: userVal.trim() };
    return null;
}

/**
 * Builds a pixelated face preview (same idea as launcher heads) from a skin PNG/JPEG blob.
 */
async function skinBlobToHeadDataUrl(blob, size = 28) {
    const bmp = await createImageBitmap(blob);
    try {
        return bitmapToHeadDataUrl(bmp, size);
    } finally {
        bmp.close();
    }
}

function bitmapToHeadDataUrl(bmp, size) {
    const w = bmp.width;
    const h = bmp.height;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext('2d');
    ctx.imageSmoothingEnabled = false;
    ctx.clearRect(0, 0, size, size);

    if (w >= 64 && h >= 64) {
        ctx.drawImage(bmp, 8, 8, 8, 8, 0, 0, size, size);
        ctx.drawImage(bmp, 40, 8, 8, 8, 0, 0, size, size);
    } else if (w === 64 && h === 32) {
        ctx.drawImage(bmp, 8, 8, 8, 8, 0, 0, size, size);
        ctx.drawImage(bmp, 40, 8, 8, 8, 0, 0, size, size);
    } else if (w === 32 && h === 32) {
        ctx.drawImage(bmp, 8, 8, 8, 8, 0, 0, size, size);
        ctx.drawImage(bmp, 24, 8, 8, 8, 0, 0, size, size);
    } else {
        ctx.drawImage(bmp, 0, 0, w, h, 0, 0, size, size);
    }

    return canvas.toDataURL('image/png');
}

async function resolveSkinBlobForPreview(src) {
    if (src.kind === 'file') {
        return src.file;
    }
    if (src.kind === 'url') {
        const res = await fetch(src.url, { mode: 'cors' });
        if (!res.ok) {
            throw new Error(`Could not download image (${res.status}). Check the URL or try uploading the file.`);
        }
        return res.blob();
    }
    if (src.kind === 'user') {
        const name = encodeURIComponent(src.username);
        const urls = [
            `https://minotar.net/skin/${name}`,
            `https://mc-heads.net/skin/${name}`,
        ];
        let lastErr = null;
        for (const u of urls) {
            try {
                const res = await fetch(u, { mode: 'cors' });
                if (!res.ok) continue;
                const blob = await res.blob();
                if (blob.size < 64) continue;
                return blob;
            } catch (e) {
                lastErr = e;
            }
        }
        throw new Error(
            lastErr
                ? 'Could not load skin for that username (network or CORS).'
                : 'Could not find a skin for that username.'
        );
    }
    return null;
}

async function skinModalPreview() {
    const src = getSkinModalActiveSource();
    if (!src) {
        showToast('Enter a URL, choose a file, or enter a username first');
        return;
    }

    const previewBtn = document.getElementById('skinPreviewBtn');
    if (previewBtn) previewBtn.disabled = true;

    try {
        const blob = await resolveSkinBlobForPreview(src);
        if (!blob) {
            showToast('Nothing to preview');
            return;
        }

        const SkinViewer = await loadSkinView3d();
        const skinview3dLib = window.skinview3d || {};

        disposeSkinViewer();

        const section = document.getElementById('skinPreviewSection');
        const wrap = document.getElementById('skinPreviewCanvasWrap');
        const canvas = document.getElementById('skinPreviewCanvas');
        if (!section || !wrap || !canvas) return;

        section.hidden = false;
        await new Promise((r) => requestAnimationFrame(r));

        const w = Math.min(320, Math.max(240, wrap.clientWidth - 8));
        const h = Math.round((w / 320) * 400);
        canvas.width = w;
        canvas.height = h;

        skinPreviewObjectUrl = URL.createObjectURL(blob);

        skinViewerInstance = new SkinViewer({
            canvas,
            width: w,
            height: h,
        });

        if (typeof skinViewerInstance.loadSkin === 'function') {
            await skinViewerInstance.loadSkin(skinPreviewObjectUrl);
        } else {
            // Fallback for older bundles that only accept constructor skin input.
            skinViewerInstance.skin = skinPreviewObjectUrl;
        }

        if (typeof skinview3dLib.createOrbitControls === 'function') {
            const controls = skinview3dLib.createOrbitControls(skinViewerInstance);
            controls.enableZoom = true;
            controls.enablePan = false;
        }

        if ('autoRotate' in skinViewerInstance) {
            skinViewerInstance.autoRotate = true;
        }
    } catch (e) {
        console.error(e);
        showToast(e.message || 'Preview failed');
        hideSkinPreviewPanel();
    } finally {
        if (previewBtn) previewBtn.disabled = false;
    }
}

function getSkinVariant() {
    const varEl = document.getElementById('skinVariantSelect');
    const v = varEl && varEl.value ? varEl.value.trim().toLowerCase() : 'classic';
    return v === 'slim' ? 'slim' : 'classic';
}

async function skinModalLoad() {
    if (skinModalAccountId == null) {
        showToast('No account selected');
        return;
    }
    const src = getSkinModalActiveSource();
    if (!src) {
        showToast('Enter a URL, choose a file, or enter a username first');
        return;
    }

    const accountId = skinModalAccountId;
    const variant = getSkinVariant();
    const loadBtn = document.getElementById('skinLoadBtn');
    if (loadBtn) loadBtn.disabled = true;

    try {
        stopHeadReconcile(accountId);
        if (src.kind === 'file') {
            const fd = new FormData();
            fd.append('file', src.file);
            fd.append('variant', variant);
            const res = await fetch(`/api/accounts/${accountId}/skin`, {
                method: 'POST',
                body: fd,
            });
            let data = {};
            try {
                data = await res.json();
            } catch {
                /* ignore */
            }
            if (!res.ok) {
                throw new Error(data.error || `Skin upload failed (${res.status})`);
            }
        } else {
            const body =
                src.kind === 'url'
                    ? { variant, url: src.url }
                    : { variant, username: src.username };
            const res = await fetch(`/api/accounts/${accountId}/skin`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
            });
            let data = {};
            try {
                data = await res.json();
            } catch {
                /* ignore */
            }
            if (!res.ok) {
                throw new Error(data.error || `Skin upload failed (${res.status})`);
            }
        }

        skinHeadBust[accountId] = Date.now();
        persistSkinHeadBust();
        try {
            // Assume the head from the same skin bytes we uploaded; reconcile in background when Mojang matches.
            const blob = await resolveSkinBlobForPreview(src);
            skinHeadLocal[accountId] = await skinBlobToHeadDataUrl(blob, 28);
            persistSkinHeadLocal();
            refreshPlayerHeadUI();
            startHeadReconcile(accountId);
        } catch (e) {
            console.warn('Live head update skipped', e);
        }

        showToast('Skin updated on Microsoft/Mojang account');
        closeSkinModal();
        await loadAccounts();
    } catch (e) {
        console.error(e);
        showToast(e.message || 'Failed to update skin');
    } finally {
        if (loadBtn) loadBtn.disabled = false;
    }
}

function renderExportList() {
    const list = document.getElementById('exportList');
    if (accounts.length === 0) {
        list.innerHTML = '<p class="export-empty">No accounts to export.</p>';
        return;
    }
    const headTick = Math.floor(Date.now() / 10000);
    list.innerHTML = accounts.map(a => {
        const local = skinHeadLocal[a.id];
        const bust = skinHeadBust[a.id];
        const headSrc =
            local != null
                ? local
                : bust != null
                  ? `/api/accounts/${a.id}/head?size=20&t=${bust}`
                  : `/api/accounts/${a.id}/head?size=20&t=${headTick}`;
        return `
        <label class="export-item">
            <input type="checkbox" class="export-check" value="${a.id}" checked>
            <img class="player-head" src="${headSrc}" alt="" onerror="this.style.display='none'">
            <span>${escHtml(a.username)}</span>
        </label>
    `;
    }).join('');
}

function exportSelectAll() {
    document.querySelectorAll('.export-check').forEach(cb => cb.checked = true);
}

function exportDeselectAll() {
    document.querySelectorAll('.export-check').forEach(cb => cb.checked = false);
}

function getExportText() {
    const selectedIds = new Set(
        [...document.querySelectorAll('.export-check:checked')].map(cb => parseInt(cb.value))
    );
    return accounts
        .filter(a => selectedIds.has(a.id) && a.accessToken && a.refreshToken)
        .map(a => `${a.accessToken}:${a.refreshToken}`)
        .join('\n');
}

function doExport() {
    const text = getExportText();
    if (!text) {
        showToast('No accounts selected or tokens missing');
        return;
    }
    const blob = new Blob([text], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'mcap-export.txt';
    a.click();
    URL.revokeObjectURL(url);
    showToast('Exported');
}

function copyExport() {
    const text = getExportText();
    if (!text) {
        showToast('No accounts selected or tokens missing');
        return;
    }
    copyToClipboard(text);
    showToast('Copied to clipboard');
}

// --- Toast ---
function showToast(msg) {
    const toast = document.getElementById('toast');
    toast.textContent = msg;
    toast.classList.remove('hidden');
    setTimeout(() => toast.classList.add('hidden'), 3000);
}
