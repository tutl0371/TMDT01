/* ======================== */
/* WARRANTY JS - BizFlow    */
/* Shopee-inspired logic    */
/* ======================== */

const API_BASE = resolveApiBase();
let allClaims = [];
let allPolicies = [];
let selectedOrderForWarranty = null;

function resolveApiBase() {
    const configured = window.API_BASE_URL || window.API_BASE;
    if (configured) return configured.replace(/\/$/, '');
    if (window.location.protocol === 'file:') return 'http://localhost:8000/api';
    if (['localhost', '127.0.0.1'].includes(window.location.hostname) && window.location.port !== '8080') return '/api';
    return `${window.location.origin}/api`;
}

function getAuthHeaders() {
    return {
        'Authorization': `Bearer ${sessionStorage.getItem('accessToken') || ''}`,
        'Content-Type': 'application/json'
    };
}

function formatPrice(price) {
    return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 })
        .format(price || 0).replace('₫', 'đ');
}

function formatDate(value) {
    if (!value) return '-';
    const d = new Date(value);
    if (isNaN(d.getTime())) return '-';
    return d.toLocaleDateString('vi-VN');
}

function formatDateTime(value) {
    if (!value) return '-';
    const d = new Date(value);
    if (isNaN(d.getTime())) return '-';
    return d.toLocaleString('vi-VN');
}

function getStatusLabel(status) {
    const map = {
        'PENDING': 'Chờ xử lý', 'RECEIVED': 'Đã tiếp nhận', 'PROCESSING': 'Đang sửa chữa',
        'COMPLETED': 'Hoàn tất', 'REJECTED': 'Từ chối', 'REPAIRED': 'Đã sửa', 'REPLACED': 'Đã đổi'
    };
    return map[status] || status;
}

// ========================
// TABS
// ========================
function initTabs() {
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
            btn.classList.add('active');
            const tabName = btn.dataset.tab;
            if (tabName === 'claims') document.getElementById('tabClaims').classList.add('active');
            else if (tabName === 'policies') { document.getElementById('tabPolicies').classList.add('active'); loadPolicies(); }
            else if (tabName === 'create') document.getElementById('tabCreate').classList.add('active');
        });
    });
}

// ========================
// STATS
// ========================
async function loadStats() {
    try {
        const res = await fetch(`${API_BASE}/warranties/claims/stats`, { headers: getAuthHeaders() });
        if (!res.ok) return;
        const data = await res.json();
        document.getElementById('statPending').textContent = (data.pending || 0) + (data.received || 0);
        document.getElementById('statProcessing').textContent = data.processing || 0;
        document.getElementById('statCompleted').textContent = data.completed || 0;
        document.getElementById('statRejected').textContent = data.rejected || 0;
    } catch (e) { console.error('Stats error:', e); }
}

// ========================
// CLAIMS
// ========================
async function loadClaims(status, keyword) {
    try {
        let url;
        if (keyword && keyword.trim()) {
            url = `${API_BASE}/warranties/claims/search?keyword=${encodeURIComponent(keyword.trim())}`;
        } else {
            const params = new URLSearchParams();
            if (status) params.append('status', status);
            url = `${API_BASE}/warranties/claims?${params}`;
        }
        const res = await fetch(url, { headers: getAuthHeaders() });
        if (!res.ok) return;
        allClaims = await res.json();
        renderClaims(allClaims);
    } catch (e) { console.error('Load claims error:', e); }
}

function renderClaims(claims) {
    const body = document.getElementById('claimsListBody');
    const empty = document.getElementById('claimsEmpty');
    if (!claims || claims.length === 0) {
        body.innerHTML = '';
        empty.style.display = 'block';
        return;
    }
    empty.style.display = 'none';
    body.innerHTML = claims.map(c => `
        <div class="claim-row">
            <span><button class="claim-link" data-id="${c.id}">${c.claimNumber || '-'}</button></span>
            <span>${c.invoiceNumber || '-'}</span>
            <span>${c.productName || 'SP #' + c.productId}</span>
            <span>${c.customerName || c.customerPhone || 'Khách lẻ'}</span>
            <span>${formatDate(c.createdAt)}</span>
            <span><span class="status-badge status-${c.status}">${getStatusLabel(c.status)}</span></span>
            <span><button class="action-btn" data-id="${c.id}">Chi tiết</button></span>
        </div>
    `).join('');
}

async function openClaimDetail(claimId) {
    try {
        const res = await fetch(`${API_BASE}/warranties/claims/${claimId}`, { headers: getAuthHeaders() });
        if (!res.ok) return;
        const claim = await res.json();
        renderClaimDetail(claim);
        openModal('claimDetailModal');
    } catch (e) { console.error(e); }
}

function renderClaimDetail(claim) {
    // Timeline
    const steps = [
        { status: 'PENDING', label: 'Tạo phiếu', icon: '📝', date: claim.createdAt },
        { status: 'RECEIVED', label: 'Tiếp nhận', icon: '📦', date: claim.receivedAt },
        { status: 'PROCESSING', label: 'Đang sửa', icon: '🔧', date: null },
        { status: 'COMPLETED', label: 'Hoàn tất', icon: '✅', date: claim.completedAt }
    ];
    const statusOrder = ['PENDING', 'RECEIVED', 'PROCESSING', 'COMPLETED', 'REJECTED'];
    const currentIdx = statusOrder.indexOf(claim.status);

    const timeline = document.getElementById('claimTimeline');
    timeline.innerHTML = steps.map((step, i) => {
        const isDone = i < currentIdx;
        const isActive = statusOrder[i] === claim.status;
        const dotClass = isDone ? 'done' : isActive ? 'active' : 'waiting';
        return `
            <div class="timeline-step">
                <div class="timeline-dot ${dotClass}">${isDone ? '✓' : step.icon}</div>
                <div class="timeline-info">
                    <div class="step-title">${step.label}</div>
                    <div class="step-date">${step.date ? formatDateTime(step.date) : (isDone || isActive ? 'Đã xong' : 'Chưa đến')}</div>
                </div>
            </div>
        `;
    }).join('');

    // Info Grid
    const grid = document.getElementById('claimInfoGrid');
    grid.innerHTML = `
        <div class="info-item"><div class="info-label">Mã phiếu</div><div class="info-value">${claim.claimNumber}</div></div>
        <div class="info-item"><div class="info-label">Trạng thái</div><div class="info-value"><span class="status-badge status-${claim.status}">${getStatusLabel(claim.status)}</span></div></div>
        <div class="info-item"><div class="info-label">Sản phẩm</div><div class="info-value">${claim.productName || 'SP #' + claim.productId}</div></div>
        <div class="info-item"><div class="info-label">Khách hàng</div><div class="info-value">${claim.customerName || 'N/A'} ${claim.customerPhone ? '- ' + claim.customerPhone : ''}</div></div>
        <div class="info-item"><div class="info-label">BH đến</div><div class="info-value">${formatDate(claim.warrantyEnd)} ${claim.isWarrantyValid ? '✅' : '❌ Hết hạn'}</div></div>
        <div class="info-item"><div class="info-label">Mô tả lỗi</div><div class="info-value">${claim.issueDescription || '-'}</div></div>
        ${claim.resolution ? `<div class="info-item"><div class="info-label">Xử lý</div><div class="info-value">${claim.resolution}</div></div>` : ''}
        ${claim.resolutionNote ? `<div class="info-item"><div class="info-label">Ghi chú xử lý</div><div class="info-value">${claim.resolutionNote}</div></div>` : ''}
    `;

    // Actions
    const actions = document.getElementById('claimActions');
    if (claim.status === 'PENDING') {
        actions.innerHTML = `
            <button class="ghost-btn" onclick="updateClaimStatus(${claim.id}, 'REJECTED', 'Từ chối')">❌ Từ chối</button>
            <button class="primary-btn" onclick="updateClaimStatus(${claim.id}, 'RECEIVED', 'Đã tiếp nhận')">📦 Tiếp nhận</button>
        `;
    } else if (claim.status === 'RECEIVED') {
        actions.innerHTML = `<button class="primary-btn" onclick="updateClaimStatus(${claim.id}, 'PROCESSING', 'Bắt đầu sửa chữa')">🔧 Bắt đầu sửa</button>`;
    } else if (claim.status === 'PROCESSING') {
        actions.innerHTML = `
            <button class="ghost-btn" onclick="completeClaim(${claim.id}, 'REPLACE')">🔄 Đổi mới</button>
            <button class="ghost-btn" onclick="completeClaim(${claim.id}, 'REFUND')">💰 Hoàn tiền</button>
            <button class="primary-btn" onclick="completeClaim(${claim.id}, 'REPAIR')">✅ Đã sửa xong</button>
        `;
    } else {
        actions.innerHTML = `<button class="ghost-btn" onclick="closeModal('claimDetailModal')">Đóng</button>`;
    }
}

async function updateClaimStatus(id, status, note) {
    try {
        const res = await fetch(`${API_BASE}/warranties/claims/${id}/status`, {
            method: 'PUT',
            headers: getAuthHeaders(),
            body: JSON.stringify({ status, resolutionNote: note })
        });
        if (res.ok) {
            const claim = await res.json();
            renderClaimDetail(claim);
            loadClaims();
            loadStats();
        }
    } catch (e) { console.error(e); }
}

async function completeClaim(id, resolution) {
    const note = prompt('Ghi chú xử lý (nếu có):') || '';
    try {
        const res = await fetch(`${API_BASE}/warranties/claims/${id}/status`, {
            method: 'PUT',
            headers: getAuthHeaders(),
            body: JSON.stringify({ status: 'COMPLETED', resolution, resolutionNote: note })
        });
        if (res.ok) {
            const claim = await res.json();
            renderClaimDetail(claim);
            loadClaims();
            loadStats();
            alert('Phiếu bảo hành đã hoàn tất!');
        }
    } catch (e) { console.error(e); }
}

// ========================
// POLICIES
// ========================
async function loadPolicies() {
    try {
        const res = await fetch(`${API_BASE}/warranties/policies`, { headers: getAuthHeaders() });
        if (!res.ok) return;
        allPolicies = await res.json();
        renderPolicies(allPolicies);
    } catch (e) { console.error(e); }
}

function renderPolicies(policies) {
    const grid = document.getElementById('policiesGrid');
    const empty = document.getElementById('policiesEmpty');
    if (!policies || policies.length === 0) {
        grid.innerHTML = '';
        empty.style.display = 'block';
        return;
    }
    empty.style.display = 'none';
    grid.innerHTML = policies.map(p => `
        <div class="policy-card">
            <h4>${p.name}</h4>
            <div class="policy-duration">${p.durationMonths} tháng</div>
            <div class="policy-desc">${p.description || 'Không có mô tả'}</div>
            <div class="policy-actions">
                <button class="action-btn" onclick="deletePolicy(${p.id})">Xóa</button>
            </div>
        </div>
    `).join('');
}

async function savePolicy() {
    const name = document.getElementById('policyName').value.trim();
    const duration = parseInt(document.getElementById('policyDuration').value);
    const desc = document.getElementById('policyDescription').value.trim();
    if (!name || !duration) { alert('Vui lòng nhập đầy đủ tên và thời hạn'); return; }

    try {
        const res = await fetch(`${API_BASE}/warranties/policies`, {
            method: 'POST',
            headers: getAuthHeaders(),
            body: JSON.stringify({ name, durationMonths: duration, description: desc })
        });
        if (res.ok) {
            closeModal('policyFormModal');
            loadPolicies();
            // Also refresh policy select in create form
            loadPolicySelect();
        }
    } catch (e) { console.error(e); }
}

async function deletePolicy(id) {
    if (!confirm('Xóa chính sách này?')) return;
    try {
        await fetch(`${API_BASE}/warranties/policies/${id}`, { method: 'DELETE', headers: getAuthHeaders() });
        loadPolicies();
    } catch (e) { console.error(e); }
}

// ========================
// CREATE CLAIM
// ========================
async function searchOrderForWarranty() {
    const keyword = document.getElementById('warrantyOrderSearch').value.trim();
    if (!keyword) { alert('Vui lòng nhập mã đơn hàng'); return; }

    try {
        // Try by invoice number first (e.g. TC-260400007), then by numeric ID
        let res;
        if (/^\d+$/.test(keyword)) {
            res = await fetch(`${API_BASE}/orders/${keyword}`, { headers: getAuthHeaders() });
        } else {
            res = await fetch(`${API_BASE}/orders/by-invoice/${encodeURIComponent(keyword)}`, { headers: getAuthHeaders() });
        }
        if (!res.ok) {
            document.getElementById('warrantyOrderInfo').style.display = 'none';
            alert('Không tìm thấy đơn hàng');
            return;
        }
        selectedOrderForWarranty = await res.json();
        renderOrderForWarranty(selectedOrderForWarranty);
    } catch (e) { alert('Lỗi tìm đơn hàng'); console.error(e); }
}

function renderOrderForWarranty(order) {
    const box = document.getElementById('warrantyOrderInfo');
    box.style.display = 'block';
    box.innerHTML = `
        <div class="order-info-row"><span>Hóa đơn:</span> <strong>${order.invoiceNumber || '-'}</strong></div>
        <div class="order-info-row"><span>Khách hàng:</span> <strong>${order.customerName || 'Khách lẻ'}</strong></div>
        <div class="order-info-row"><span>Tổng tiền:</span> <strong>${formatPrice(order.totalAmount)}</strong></div>
        <div class="order-info-row"><span>Ngày mua:</span> <strong>${formatDate(order.createdAt)}</strong></div>
    `;

    // Show product list
    const productGroup = document.getElementById('warrantyProductGroup');
    const productList = document.getElementById('warrantyProductList');
    productGroup.style.display = 'block';
    productList.innerHTML = (order.items || []).map((item, i) => `
        <label class="product-select-item">
            <input type="radio" name="warrantyProduct" value="${item.productId}" data-index="${i}" ${i === 0 ? 'checked' : ''}>
            <span>${item.productName || 'SP #' + item.productId}</span>
            <span style="margin-left:auto; color:#6b7280">${item.quantity}x ${formatPrice(item.price)}</span>
        </label>
    `).join('');

    document.getElementById('warrantyPolicyGroup').style.display = 'block';
    document.getElementById('warrantyIssueGroup').style.display = 'block';
    document.getElementById('warrantyFormActions').style.display = 'flex';
    loadPolicySelect();
}

async function loadPolicySelect() {
    try {
        const res = await fetch(`${API_BASE}/warranties/policies`, { headers: getAuthHeaders() });
        if (!res.ok) return;
        const policies = await res.json();
        const select = document.getElementById('warrantyPolicySelect');
        select.innerHTML = '<option value="">-- Mặc định 12 tháng --</option>' +
            policies.map(p => `<option value="${p.id}">${p.name} (${p.durationMonths} tháng)</option>`).join('');
    } catch (e) { console.error(e); }
}

async function submitWarrantyForm() {
    if (!selectedOrderForWarranty) { alert('Vui lòng tìm đơn hàng trước'); return; }
    const radio = document.querySelector('input[name="warrantyProduct"]:checked');
    if (!radio) { alert('Vui lòng chọn sản phẩm'); return; }
    const desc = document.getElementById('warrantyIssueDesc').value.trim();
    if (!desc) { alert('Vui lòng mô tả lỗi sản phẩm'); return; }

    const productId = parseInt(radio.value);
    const policyId = document.getElementById('warrantyPolicySelect').value || null;
    const idx = parseInt(radio.dataset.index);
    const item = selectedOrderForWarranty.items[idx];

    try {
        const res = await fetch(`${API_BASE}/warranties/claims`, {
            method: 'POST',
            headers: getAuthHeaders(),
            body: JSON.stringify({
                orderId: selectedOrderForWarranty.id,
                productId: productId,
                orderItemId: item ? item.id : null,
                customerId: selectedOrderForWarranty.customerId,
                policyId: policyId ? parseInt(policyId) : null,
                issueDescription: desc,
                createdBy: parseInt(sessionStorage.getItem('userId')) || null
            })
        });
        if (res.ok) {
            const claim = await res.json();
            alert(`Tạo phiếu bảo hành thành công: ${claim.claimNumber}`);
            resetWarrantyForm();
            loadClaims();
            loadStats();
            // Switch to claims tab
            document.querySelector('.tab-btn[data-tab="claims"]').click();
        } else {
            const msg = await res.text();
            alert(msg || 'Lỗi tạo phiếu bảo hành');
        }
    } catch (e) { alert('Lỗi kết nối'); console.error(e); }
}

function resetWarrantyForm() {
    selectedOrderForWarranty = null;
    document.getElementById('warrantyOrderSearch').value = '';
    document.getElementById('warrantyOrderInfo').style.display = 'none';
    document.getElementById('warrantyProductGroup').style.display = 'none';
    document.getElementById('warrantyPolicyGroup').style.display = 'none';
    document.getElementById('warrantyIssueGroup').style.display = 'none';
    document.getElementById('warrantyFormActions').style.display = 'none';
    document.getElementById('warrantyIssueDesc').value = '';
}

// ========================
// MODALS
// ========================
function openModal(id) {
    const modal = document.getElementById(id);
    if (modal) { modal.classList.add('show'); modal.setAttribute('aria-hidden', 'false'); }
}
function closeModal(id) {
    const modal = document.getElementById(id);
    if (modal) { modal.classList.remove('show'); modal.setAttribute('aria-hidden', 'true'); }
}

// ========================
// INIT
// ========================
document.addEventListener('DOMContentLoaded', () => {
    const token = sessionStorage.getItem('accessToken');
    if (!token) { window.location.href = '/pages/login.html'; return; }

    initTabs();
    loadStats();
    loadClaims();

    // Event listeners
    document.getElementById('claimSearchBtn')?.addEventListener('click', () => {
        const keyword = document.getElementById('claimSearchInput').value;
        const status = document.getElementById('claimStatusFilter').value;
        if (keyword.trim()) loadClaims(null, keyword);
        else loadClaims(status || null);
    });
    document.getElementById('claimStatusFilter')?.addEventListener('change', (e) => {
        loadClaims(e.target.value || null);
    });

    document.getElementById('claimsListBody')?.addEventListener('click', (e) => {
        const link = e.target.closest('.claim-link') || e.target.closest('.action-btn');
        if (link) openClaimDetail(parseInt(link.dataset.id));
    });

    document.getElementById('closeClaimDetail')?.addEventListener('click', () => closeModal('claimDetailModal'));
    document.getElementById('claimDetailModal')?.addEventListener('click', (e) => {
        if (e.target === e.currentTarget) closeModal('claimDetailModal');
    });

    // Policies
    document.getElementById('addPolicyBtn')?.addEventListener('click', () => {
        document.getElementById('policyName').value = '';
        document.getElementById('policyDuration').value = '12';
        document.getElementById('policyDescription').value = '';
        openModal('policyFormModal');
    });
    document.getElementById('savePolicyForm')?.addEventListener('click', savePolicy);
    document.getElementById('cancelPolicyForm')?.addEventListener('click', () => closeModal('policyFormModal'));
    document.getElementById('closePolicyForm')?.addEventListener('click', () => closeModal('policyFormModal'));

    // Create
    document.getElementById('searchOrderForWarranty')?.addEventListener('click', searchOrderForWarranty);
    document.getElementById('warrantyOrderSearch')?.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') searchOrderForWarranty();
    });
    document.getElementById('submitWarrantyForm')?.addEventListener('click', submitWarrantyForm);
    document.getElementById('resetWarrantyForm')?.addEventListener('click', resetWarrantyForm);
});
