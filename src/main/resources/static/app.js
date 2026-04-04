// --- State ---
let accounts = [];
let refreshTimer = null;

// --- Init ---
document.addEventListener('DOMContentLoaded', () => {
    loadAccounts();
    refreshTimer = setInterval(() => {
        loadAccounts();
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
        renderAccounts();
    } catch (e) {
        console.error('Failed to load accounts', e);
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
    tbody.innerHTML = accounts.map(a => {
        const headUrl = `https://mc-heads.net/avatar/${a.uuid}/28`;
        const expiry = formatExpiry(a.tokenExpiry);
        return `<tr>
            <td class="checkbox-col"><input type="checkbox" class="account-check" value="${a.id}" onchange="updateSelectAllCheck()"></td>
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
                    <button class="btn btn-sm btn-danger" onclick="deleteAccount(${a.id})">Delete</button>
                </div>
            </td>
        </tr>`;
    }).join('');
    updateSelectAllCheck();
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

// --- Toast ---
function showToast(msg) {
    const toast = document.getElementById('toast');
    toast.textContent = msg;
    toast.classList.remove('hidden');
    setTimeout(() => toast.classList.add('hidden'), 3000);
}
