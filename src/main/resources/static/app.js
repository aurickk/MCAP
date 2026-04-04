// --- State ---
let accounts = [];
let refreshTimer = null;
let expandedIds = new Set();
let textureCache = {};
let skinViewers = {};
let initialLoad = true;

// --- Init ---
document.addEventListener('DOMContentLoaded', () => {
    loadAccounts();
    refreshTimer = setInterval(() => {
        if (expandedIds.size === 0) {
            loadAccounts();
        }
    }, 10000);
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
        initialLoad = false;
        renderAccounts();
    } catch (e) {
        console.error('Failed to load accounts', e);
        if (initialLoad) {
            const tbody = document.getElementById('accountBody');
            tbody.innerHTML = '<tr class="empty-row"><td colspan="5">Failed to load accounts.</td></tr>';
            initialLoad = false;
        }
    }
}

// --- Rendering ---
function renderAccounts() {
    const tbody = document.getElementById('accountBody');
    if (accounts.length === 0) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="5">No accounts yet. Click "Add Account" to get started.</td></tr>';
        updateSelectAllCheck();
        return;
    }
    // Dispose existing 3D viewers before rebuilding DOM
    for (const vid of Object.keys(skinViewers)) {
        skinViewers[vid].dispose();
        delete skinViewers[vid];
    }

    tbody.innerHTML = accounts.map(a => {
        const headUrl = `https://mc-heads.net/avatar/${a.uuid}/28`;
        const expiry = formatExpiry(a.tokenExpiry);
        const isExpanded = expandedIds.has(a.id);
        return `<tr class="account-row">
            <td class="checkbox-col">
                <input type="checkbox" class="account-check" value="${a.id}" onchange="updateSelectAllCheck()">
            </td>
            <td>
                <div class="player-cell">
                    <img class="player-head" src="${headUrl}" alt="${a.username}" onerror="this.style.display='none'">
                    <span>${escHtml(a.username)}</span>
                </div>
            </td>
            <td class="uuid-cell">${escHtml(a.uuid)}</td>
            <td class="expiry-col"><span class="expiry-text ${isExpired(a.tokenExpiry) ? 'expired' : ''}">${expiry}</span></td>
            <td>
                <div class="actions">
                    ${a.accessToken ? `<button class="btn btn-sm btn-secondary" onclick="copySession(${a.id})">Copy Session</button>` : ''}
                    ${a.refreshToken ? `<button class="btn btn-sm btn-secondary" onclick="copyRefresh(${a.id})">Copy Refresh</button>` : ''}
                    <button class="btn btn-sm btn-secondary" onclick="refreshToken(${a.id})">Refresh</button>
                    <button class="btn-expand ${isExpanded ? 'expanded' : ''}" onclick="toggleExpand(${a.id})" title="Expand details">&#9654;</button>
                </div>
            </td>
        </tr>
        <tr class="detail-row ${isExpanded ? '' : 'hidden'}" id="detail-${a.id}">
            <td colspan="5">
                <div class="detail-panel">
                    <div class="detail-skin">
                        <canvas class="skin-canvas" id="skinCanvas-${a.id}" width="200" height="300"></canvas>
                    </div>
                    <div class="detail-forms">
                        <div class="form-section">
                            <h4>Change Skin</h4>
                            <div class="form-row">
                                <button class="btn btn-sm btn-secondary hidden" id="skinClear-${a.id}" onclick="clearSkinFile(${a.id})" title="Clear">&#10005;</button>
                                <input type="file" accept=".png" id="skinFile-${a.id}" class="file-input" onchange="previewSkinFile(${a.id})">
                            </div>
                            <div class="form-row">
                                <select id="skinVariant-${a.id}" class="variant-select" onchange="onVariantChange(${a.id})">
                                    <option value="classic">Classic</option>
                                    <option value="slim">Slim</option>
                                </select>
                                <button class="btn btn-sm btn-primary" onclick="uploadSkin(${a.id})">Upload Skin</button>
                            </div>
                        </div>
                        <div class="form-section">
                            <h4>Capes</h4>
                            <div class="cape-list" id="capeList-${a.id}">
                                <span class="cape-loading">Loading...</span>
                            </div>
                        </div>
                    </div>
                    <div class="detail-forms-right">
                        <div class="form-section">
                            <h4>Change Username</h4>
                            <div class="form-row">
                                <input type="text" id="nameInput-${a.id}" placeholder="New username" class="name-input" maxlength="16">
                                <button class="btn btn-sm btn-secondary" onclick="checkName(${a.id})">Check</button>
                            </div>
                            <div class="name-status-row">
                                <div id="nameStatus-${a.id}" class="name-status">&nbsp;</div>
                                <button class="btn btn-sm btn-primary" onclick="changeName(${a.id})" id="nameChangeBtn-${a.id}" disabled>Change Name</button>
                            </div>
                        </div>
                    </div>
                </div>
            </td>
        </tr>`;
    }).join('');
    updateSelectAllCheck();

    // Recreate 3D viewers for expanded rows (canvases were rebuilt)
    for (const id of expandedIds) {
        if (textureCache[id]) {
            createSkinViewer(id, textureCache[id]);
        } else {
            loadProfile(id);
        }
    }
}

function formatExpiry(ms) {
    if (!ms || ms === 0) return 'N/A';
    const now = Date.now();
    if (ms < now) return 'Expired';
    const diff = ms - now;
    const mins = Math.floor(diff / 60000);
    if (mins < 60) return `${mins}m`;
    const hours = Math.floor(mins / 60);
    return `${hours}h ${mins % 60}m`;
}

function isExpired(ms) {
    return ms && ms > 0 && ms < Date.now();
}

function escHtml(str) {
    const div = document.createElement('div');
    div.textContent = str || '';
    return div.innerHTML;
}

// --- Expand/Collapse ---
function toggleExpand(id) {
    const detailRow = document.getElementById(`detail-${id}`);
    const mainRow = detailRow.previousElementSibling;
    const btn = mainRow.querySelector('.btn-expand');
    if (expandedIds.has(id)) {
        expandedIds.delete(id);
        detailRow.classList.add('hidden');
        btn.classList.remove('expanded');
        disposeSkinViewer(id);
    } else {
        expandedIds.add(id);
        detailRow.classList.remove('hidden');
        btn.classList.add('expanded');
        loadProfile(id);
    }
}

function disposeSkinViewer(id) {
    if (skinViewers[id]) {
        skinViewers[id].dispose();
        delete skinViewers[id];
    }
}

function createSkinViewer(id, data) {
    disposeSkinViewer(id);
    const canvas = document.getElementById(`skinCanvas-${id}`);
    if (!canvas) return;

    const viewer = new skinview3d.SkinViewer({
        canvas: canvas,
        width: 200,
        height: 300,
        animation: new skinview3d.IdleAnimation()
    });
    viewer.zoom = 0.85;
    skinViewers[id] = viewer;

    // Load skin and cape directly from Mojang (serves CORS headers)
    if (data.skinUrl) {
        viewer.loadSkin(data.skinUrl, {
            model: data.skinModel === 'slim' ? 'slim' : 'default'
        });
    }
    if (data.capeUrl) {
        viewer.loadCape(data.capeUrl);
    }
}

async function loadProfile(id) {
    if (textureCache[id]) {
        applyProfile(id, textureCache[id]);
        return;
    }
    try {
        const data = await api(`/api/accounts/${id}/profile`);
        if (!data.error) {
            textureCache[id] = data;
            applyProfile(id, data);
        }
    } catch (e) {
        console.error('Failed to load profile for account', id, e);
    }
}

function applyProfile(id, data) {
    // Set variant select to match current skin model
    const variantSelect = document.getElementById(`skinVariant-${id}`);
    if (variantSelect && data.skinModel) {
        variantSelect.value = data.skinModel;
    }

    // Create 3D skin viewer (skin + active cape)
    const activeCape = data.capes ? data.capes.find(c => c.state === 'ACTIVE') : null;
    createSkinViewer(id, { skinUrl: data.skinUrl, skinModel: data.skinModel, capeUrl: activeCape ? activeCape.url : null });

    // Render cape list
    if (data.capes) {
        capeCache[id] = data.capes;
        activeCapeUrl[id] = activeCape ? activeCape.url : null;
        renderCapeList(id, data.capes);
    }
}

// --- Skin Upload ---
async function uploadSkin(id) {
    const fileInput = document.getElementById(`skinFile-${id}`);
    const variantSelect = document.getElementById(`skinVariant-${id}`);
    if (!fileInput.files.length) {
        showToast('Please select a skin file');
        return;
    }
    const formData = new FormData();
    formData.append('file', fileInput.files[0]);
    formData.append('variant', variantSelect.value);

    showToast('Uploading skin...');
    try {
        const res = await fetch(`/api/accounts/${id}/skin`, { method: 'POST', body: formData });
        const result = await res.json();
        if (result.error) {
            showToast(result.error);
        } else {
            showToast('Skin updated successfully');
            // Bust cache and reload 3D viewer
            delete textureCache[id];
            disposeSkinViewer(id);
            loadProfile(id);
            loadAccounts();
        }
    } catch (e) {
        showToast('Skin upload failed');
    }
}

// --- Name Check & Change ---
async function checkName(id) {
    const nameInput = document.getElementById(`nameInput-${id}`);
    const nameStatus = document.getElementById(`nameStatus-${id}`);
    const changeBtn = document.getElementById(`nameChangeBtn-${id}`);
    const name = nameInput.value.trim();

    if (!name || name.length < 3) {
        nameStatus.textContent = 'Min 3 characters';
        nameStatus.className = 'name-status name-taken';
        changeBtn.disabled = true;
        return;
    }

    nameStatus.textContent = 'Checking...';
    nameStatus.className = 'name-status';
    try {
        const result = await api(`/api/accounts/name/${encodeURIComponent(name)}/available`);
        if (result.error) {
            nameStatus.textContent = result.error;
            nameStatus.className = 'name-status name-taken';
            changeBtn.disabled = true;
        } else if (result.status === 'AVAILABLE') {
            nameStatus.textContent = 'Available';
            nameStatus.className = 'name-status name-available';
            changeBtn.disabled = false;
        } else if (result.status === 'DUPLICATE') {
            nameStatus.textContent = 'Taken';
            nameStatus.className = 'name-status name-taken';
            changeBtn.disabled = true;
        } else {
            nameStatus.textContent = result.status || 'Not available';
            nameStatus.className = 'name-status name-taken';
            changeBtn.disabled = true;
        }
    } catch (e) {
        nameStatus.textContent = 'Check failed';
        nameStatus.className = 'name-status name-taken';
        changeBtn.disabled = true;
    }
}

async function changeName(id) {
    const nameInput = document.getElementById(`nameInput-${id}`);
    const nameStatus = document.getElementById(`nameStatus-${id}`);
    const changeBtn = document.getElementById(`nameChangeBtn-${id}`);
    const name = nameInput.value.trim();
    if (!name) return;

    if (!confirm(`Change username to "${name}"? This has a 30-day cooldown.`)) return;

    nameStatus.textContent = 'Changing...';
    nameStatus.className = 'name-status';
    changeBtn.disabled = true;
    try {
        const res = await fetch(`/api/accounts/${id}/name`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name })
        });
        const result = await res.json();
        if (result.error) {
            nameStatus.textContent = result.error;
            nameStatus.className = 'name-status name-taken';
            showToast(result.error);
        } else {
            nameStatus.textContent = `Changed to ${result.username}`;
            nameStatus.className = 'name-status name-available';
            showToast(`Username changed to ${result.username}`);
            delete textureCache[id];
            loadAccounts();
        }
    } catch (e) {
        nameStatus.textContent = 'Name change failed';
        nameStatus.className = 'name-status name-taken';
        showToast('Name change failed');
    }
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

async function deleteSelected() {
    const selectedIds = [...document.querySelectorAll('.account-check:checked')].map(cb => parseInt(cb.value));
    if (selectedIds.length === 0) return;
    try {
        await Promise.all(selectedIds.map(id => {
            expandedIds.delete(id);
            delete textureCache[id];
            disposeSkinViewer(id);
            return api(`/api/accounts/${id}`, { method: 'DELETE' });
        }));
        showToast(`${selectedIds.length} account${selectedIds.length > 1 ? 's' : ''} removed`);
        loadAccounts();
    } catch (e) {
        showToast('Delete failed');
        loadAccounts();
    }
}

// --- Login Modal ---
function openLoginModal() {
    document.getElementById('loginModal').classList.add('active');
    resetLoginModal();
    startLogin();
}

function closeLoginModal() {
    document.getElementById('loginModal').classList.remove('active');
    resetLoginModal();
}

function resetLoginModal() {
    document.getElementById('loginCode').classList.add('hidden');
    document.getElementById('loginSuccess').classList.add('hidden');
    document.getElementById('loginError').classList.add('hidden');
}

function startLogin() {
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
    showToast('Link copied');
}

// --- Selection & Export ---
function selectAll() {
    document.querySelectorAll('.account-check').forEach(cb => cb.checked = true);
    updateSelectAllCheck();
}

function deselectAll() {
    document.querySelectorAll('.account-check').forEach(cb => cb.checked = false);
    updateSelectAllCheck();
}

function toggleSelectAll(headerCheckbox) {
    document.querySelectorAll('.account-check').forEach(cb => cb.checked = headerCheckbox.checked);
    updateSelectAllCheck();
}

function updateSelectAllCheck() {
    const all = document.querySelectorAll('.account-check');
    const checked = document.querySelectorAll('.account-check:checked');
    const headerCheck = document.getElementById('selectAllCheck');
    if (!headerCheck) return;
    headerCheck.checked = all.length > 0 && checked.length === all.length;
    headerCheck.indeterminate = checked.length > 0 && checked.length < all.length;

    const hasSelection = checked.length > 0;
    document.getElementById('exportSeparator').classList.toggle('hidden', !hasSelection);
    document.getElementById('exportTxtBtn').classList.toggle('hidden', !hasSelection);
    document.getElementById('copyExportBtn').classList.toggle('hidden', !hasSelection);
    document.getElementById('deleteSelectedBtn').classList.toggle('hidden', !hasSelection);
}

function getExportText() {
    const selectedIds = new Set(
        [...document.querySelectorAll('.account-check:checked')].map(cb => parseInt(cb.value))
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

// --- Skin Preview ---
function previewSkinFile(id) {
    const fileInput = document.getElementById(`skinFile-${id}`);
    const clearBtn = document.getElementById(`skinClear-${id}`);
    const viewer = skinViewers[id];
    if (!fileInput.files.length) {
        clearBtn.classList.add('hidden');
        return;
    }
    clearBtn.classList.remove('hidden');
    if (!viewer) return;
    const variantSelect = document.getElementById(`skinVariant-${id}`);
    const model = variantSelect.value === 'slim' ? 'slim' : 'default';
    const reader = new FileReader();
    reader.onload = (e) => {
        const img = new Image();
        img.onload = () => viewer.loadSkin(img, { model });
        img.src = e.target.result;
    };
    reader.readAsDataURL(fileInput.files[0]);
}

function clearSkinFile(id) {
    const fileInput = document.getElementById(`skinFile-${id}`);
    fileInput.value = '';
    document.getElementById(`skinClear-${id}`).classList.add('hidden');
    // Restore original skin
    const data = textureCache[id];
    const viewer = skinViewers[id];
    if (viewer && data && data.skinUrl) {
        viewer.loadSkin(data.skinUrl, {
            model: data.skinModel === 'slim' ? 'slim' : 'default'
        });
    } else if (viewer) {
        viewer.loadSkin(null);
    }
}

function onVariantChange(id) {
    const fileInput = document.getElementById(`skinFile-${id}`);
    if (fileInput.files.length) {
        previewSkinFile(id);
    }
}

// --- Capes ---
let capeCache = {}; // id -> capes array
let activeCapeUrl = {}; // id -> current active cape url (for restoring after hover)

function renderCapeList(id, capes) {
    const container = document.getElementById(`capeList-${id}`);
    if (!container) return;
    if (capes.length === 0) {
        container.innerHTML = '<span class="cape-loading">No capes</span>';
        return;
    }
    container.innerHTML = capes.map(c => {
        const active = c.state === 'ACTIVE';
        return `<div class="cape-item ${active ? 'cape-active' : ''}"
            onclick="equipCape(${id}, '${c.id}')"
            onmouseenter="previewCape(${id}, '${escHtml(c.url)}')"
            onmouseleave="restoreCape(${id})"
            title="${escHtml(c.alias)}${active ? ' (active)' : ''}">
            <canvas class="cape-thumb" data-cape-url="${escHtml(c.url)}" width="20" height="32"></canvas>
            ${active ? '<div class="cape-active-dot"></div>' : ''}
        </div>`;
    }).join('') + `<div class="cape-item cape-hide"
        onclick="hideCape(${id})"
        onmouseenter="previewCape(${id}, null)"
        onmouseleave="restoreCape(${id})"
        title="Hide cape">
        <div class="cape-hide-icon">&#10005;</div>
    </div>`;
    // Render cape front faces
    container.querySelectorAll('.cape-thumb').forEach(canvas => {
        renderCapeFront(canvas, canvas.dataset.capeUrl);
    });
}

function renderCapeFront(canvas, url) {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => {
        const scale = img.width / 64;
        const ctx = canvas.getContext('2d');
        ctx.imageSmoothingEnabled = false;
        canvas.width = 10 * scale;
        canvas.height = 16 * scale;
        ctx.drawImage(img, 1 * scale, 1 * scale, 10 * scale, 16 * scale, 0, 0, 10 * scale, 16 * scale);
    };
    img.src = url;
}

function previewCape(id, url) {
    const viewer = skinViewers[id];
    if (!viewer) return;
    if (url) {
        viewer.loadCape(url);
    } else {
        viewer.loadCape(null);
    }
}

function restoreCape(id) {
    const viewer = skinViewers[id];
    if (!viewer) return;
    if (activeCapeUrl[id]) {
        viewer.loadCape(activeCapeUrl[id]);
    } else {
        viewer.loadCape(null);
    }
}

async function equipCape(id, capeId) {
    showToast('Changing cape...');
    try {
        const res = await fetch(`/api/accounts/${id}/cape`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ capeId })
        });
        const result = await res.json();
        if (result.error) {
            showToast(result.error);
        } else {
            showToast('Cape changed');
            // Update active cape without recreating viewer
            const capes = capeCache[id];
            if (capes) {
                capes.forEach(c => c.state = c.id === capeId ? 'ACTIVE' : 'INACTIVE');
                const active = capes.find(c => c.id === capeId);
                activeCapeUrl[id] = active ? active.url : null;
                renderCapeList(id, capes);
            }
            delete textureCache[id];
        }
    } catch (e) {
        showToast('Failed to change cape');
    }
}

async function hideCape(id) {
    showToast('Hiding cape...');
    try {
        const res = await fetch(`/api/accounts/${id}/cape`, { method: 'DELETE' });
        const result = await res.json();
        if (result.error) {
            showToast(result.error);
        } else {
            showToast('Cape hidden');
            const capes = capeCache[id];
            if (capes) {
                capes.forEach(c => c.state = 'INACTIVE');
                activeCapeUrl[id] = null;
                renderCapeList(id, capes);
            }
            const viewer = skinViewers[id];
            if (viewer) viewer.loadCape(null);
            delete textureCache[id];
        }
    } catch (e) {
        showToast('Failed to hide cape');
    }
}

// --- Toast ---
function showToast(msg) {
    const toast = document.getElementById('toast');
    toast.textContent = msg;
    toast.classList.remove('hidden');
    setTimeout(() => toast.classList.add('hidden'), 3000);
}
