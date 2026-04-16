/* ======================== */
/* SHIPPING JS - BizFlow    */
/* Shopee-inspired logic    */
/* ======================== */

const API_BASE = resolveApiBase();
let allShipments = [];
let selectedOrderForShip = null;

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
    return isNaN(d.getTime()) ? '-' : d.toLocaleDateString('vi-VN');
}

function formatDateTime(value) {
    if (!value) return '-';
    const d = new Date(value);
    return isNaN(d.getTime()) ? '-' : d.toLocaleString('vi-VN');
}

function getStatusLabel(status) {
    const map = {
        'PENDING': 'Chờ xác nhận', 'CONFIRMED': 'Đã xác nhận', 'PICKED_UP': 'Đã lấy hàng',
        'IN_TRANSIT': 'Đang giao', 'DELIVERED': 'Đã giao', 'FAILED': 'Thất bại',
        'CANCELLED': 'Đã hủy', 'RETURNING': 'Đang hoàn'
    };
    return map[status] || status;
}

function getMethodLabel(method) {
    const map = {
        'STORE_DELIVERY': '🏪 Cửa hàng giao', 'GHN': '🚀 GHN', 'GHTK': '📦 GHTK',
        'VIETTEL_POST': '📮 Viettel Post', 'SELF_PICKUP': '🙋 Tự đến lấy'
    };
    return map[method] || method;
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
            if (tabName === 'list') document.getElementById('tabList').classList.add('active');
            else if (tabName === 'create') document.getElementById('tabCreate').classList.add('active');
        });
    });
}

// ========================
// STATS
// ========================
async function loadStats() {
    try {
        const res = await fetch(`${API_BASE}/shipments/stats`, { headers: getAuthHeaders() });
        if (!res.ok) return;
        const data = await res.json();
        document.getElementById('statPending').textContent = (data.pending || 0) + (data.confirmed || 0);
        document.getElementById('statInTransit').textContent = (data.pickedUp || 0) + (data.inTransit || 0);
        document.getElementById('statDelivered').textContent = data.delivered || 0;
        document.getElementById('statFailed').textContent = (data.failed || 0) + (data.cancelled || 0);
    } catch (e) { console.error('Stats error:', e); }
}

// ========================
// SHIPMENT LIST
// ========================
async function loadShipments(status, keyword) {
    try {
        let url;
        if (keyword && keyword.trim()) {
            url = `${API_BASE}/shipments/search?keyword=${encodeURIComponent(keyword.trim())}`;
        } else {
            const params = new URLSearchParams();
            if (status) params.append('status', status);
            url = `${API_BASE}/shipments?${params}`;
        }
        const res = await fetch(url, { headers: getAuthHeaders() });
        if (!res.ok) return;
        allShipments = await res.json();
        renderShipments(allShipments);
    } catch (e) { console.error('Load shipments error:', e); }
}

function renderShipments(shipments) {
    const body = document.getElementById('shipmentsListBody');
    const empty = document.getElementById('shipmentsEmpty');
    if (!shipments || shipments.length === 0) {
        body.innerHTML = '';
        empty.style.display = 'block';
        return;
    }
    empty.style.display = 'none';
    body.innerHTML = shipments.map(s => `
        <div class="ship-row">
            <span><button class="ship-link" data-id="${s.id}">${s.shipmentNumber || '-'}</button></span>
            <span>${s.invoiceNumber || s.orderId || '-'}</span>
            <span>${s.receiverName || '-'}</span>
            <span>${s.receiverPhone || '-'}</span>
            <span>${s.shippingMethodLabel || getMethodLabel(s.shippingMethod)}</span>
            <span><span class="status-badge status-${s.status}">${s.statusLabel || getStatusLabel(s.status)}</span></span>
            <span><button class="action-btn" data-id="${s.id}">Chi tiết</button></span>
        </div>
    `).join('');
}

// ========================
// SHIPMENT DETAIL (Shopee tracking style)
// ========================
async function openShipDetail(shipId) {
    try {
        const res = await fetch(`${API_BASE}/shipments/${shipId}`, { headers: getAuthHeaders() });
        if (!res.ok) return;
        const shipment = await res.json();
        renderShipDetail(shipment);
        openModal('shipDetailModal');
    } catch (e) { console.error(e); }
}

function renderShipDetail(ship) {
    // Status Banner
    const banner = document.getElementById('shipStatusBanner');
    let bannerClass = 'banner-pending';
    let bannerIcon = '📦';
    if (['IN_TRANSIT', 'PICKED_UP'].includes(ship.status)) { bannerClass = 'banner-transit'; bannerIcon = '🚚'; }
    else if (ship.status === 'DELIVERED') { bannerClass = 'banner-delivered'; bannerIcon = '✅'; }
    else if (['FAILED', 'CANCELLED'].includes(ship.status)) { bannerClass = 'banner-failed'; bannerIcon = '⚠️'; }
    banner.className = `ship-status-banner ${bannerClass}`;
    banner.innerHTML = `<span style="font-size:20px">${bannerIcon}</span> ${ship.statusLabel || getStatusLabel(ship.status)}`;

    // Info Grid
    const grid = document.getElementById('shipInfoGrid');
    grid.innerHTML = `
        <div class="info-item"><div class="info-label">Mã vận đơn</div><div class="info-value">${ship.shipmentNumber}</div></div>
        <div class="info-item"><div class="info-label">Đơn hàng</div><div class="info-value">#${ship.orderId}</div></div>
        <div class="info-item"><div class="info-label">Người nhận</div><div class="info-value">${ship.receiverName || '-'}</div></div>
        <div class="info-item"><div class="info-label">SĐT</div><div class="info-value">${ship.receiverPhone || '-'}</div></div>
        <div class="info-item" style="grid-column:1/-1"><div class="info-label">Địa chỉ</div><div class="info-value">${ship.receiverAddress || '-'}</div></div>
        <div class="info-item"><div class="info-label">ĐVVC</div><div class="info-value">${ship.shippingMethodLabel || getMethodLabel(ship.shippingMethod)}</div></div>
        <div class="info-item"><div class="info-label">Phí ship</div><div class="info-value">${formatPrice(ship.shippingFee)}</div></div>
        <div class="info-item"><div class="info-label">Dự kiến giao</div><div class="info-value">${formatDate(ship.estimatedDelivery)}</div></div>
        ${ship.trackingCode ? `<div class="info-item"><div class="info-label">Mã tracking</div><div class="info-value">${ship.trackingCode}</div></div>` : ''}
    `;

    // Tracking Timeline (Shopee style)
    const timeline = document.getElementById('trackingTimeline');
    const history = ship.trackingHistory || [];
    if (history.length > 0) {
        timeline.innerHTML = history.map(t => `
            <div class="tracking-item">
                <div class="tracking-status">${getStatusLabel(t.status)}</div>
                <div class="tracking-note">${t.note || ''}</div>
                ${t.location ? `<div class="tracking-location">📍 ${t.location}</div>` : ''}
                <div class="tracking-time">${formatDateTime(t.createdAt)}</div>
            </div>
        `).join('');
    } else {
        timeline.innerHTML = '<div style="color:#9ca3af;font-size:13px">Chưa có lịch sử vận chuyển</div>';
    }

    // Actions
    const actions = document.getElementById('shipActions');
    const nextAction = getNextAction(ship.status);
    if (nextAction) {
        actions.innerHTML = `
            ${ship.status !== 'DELIVERED' ? `<button class="ghost-btn" onclick="updateShipStatus(${ship.id}, 'CANCELLED')">Hủy đơn</button>` : ''}
            <button class="primary-btn" onclick="updateShipStatus(${ship.id}, '${nextAction.status}')">${nextAction.label}</button>
        `;
    } else {
        actions.innerHTML = `<button class="ghost-btn" onclick="closeModal('shipDetailModal')">Đóng</button>`;
    }
}

function getNextAction(status) {
    switch (status) {
        case 'PENDING': return { status: 'CONFIRMED', label: '✅ Xác nhận đơn' };
        case 'CONFIRMED': return { status: 'PICKED_UP', label: '📦 Đã lấy hàng' };
        case 'PICKED_UP': return { status: 'IN_TRANSIT', label: '🚚 Bắt đầu giao' };
        case 'IN_TRANSIT': return { status: 'DELIVERED', label: '✅ Đã giao thành công' };
        default: return null;
    }
}

async function updateShipStatus(id, status) {
    const note = (status === 'CANCELLED' || status === 'FAILED')
        ? prompt('Lý do:') || ''
        : '';
    try {
        const res = await fetch(`${API_BASE}/shipments/${id}/status`, {
            method: 'PUT',
            headers: getAuthHeaders(),
            body: JSON.stringify({
                status,
                note: note || undefined,
                updatedBy: parseInt(sessionStorage.getItem('userId')) || null
            })
        });
        if (res.ok) {
            openShipDetail(id);
            loadShipments();
            loadStats();
        }
    } catch (e) { console.error(e); }
}

// ========================
// CREATE SHIPMENT
// ========================
async function searchOrderForShip() {
    const keyword = document.getElementById('shipOrderId').value.trim();
    if (!keyword) { alert('Vui lòng nhập mã đơn hàng'); return; }

    try {
        let res;
        // Try by invoice number first if it's not a pure numeric ID
        if (/^\d+$/.test(keyword)) {
            res = await fetch(`${API_BASE}/orders/${keyword}`, { headers: getAuthHeaders() });
        } else {
            res = await fetch(`${API_BASE}/orders/by-invoice/${encodeURIComponent(keyword)}`, { headers: getAuthHeaders() });
        }

        if (!res.ok) {
            document.getElementById('shipOrderInfo').style.display = 'none';
            document.getElementById('shipFormFields').style.display = 'none';
            alert('Không tìm thấy đơn hàng');
            return;
        }
        selectedOrderForShip = await res.json();
        renderOrderForShip(selectedOrderForShip);
    } catch (e) { alert('Lỗi tìm đơn hàng'); console.error(e); }
}

function renderOrderForShip(order) {
    const box = document.getElementById('shipOrderInfo');
    box.style.display = 'block';
    box.innerHTML = `
        <div style="display:flex;justify-content:space-between;margin-bottom:4px"><span>Hóa đơn:</span> <strong>${order.invoiceNumber || '-'}</strong></div>
        <div style="display:flex;justify-content:space-between;margin-bottom:4px"><span>Khách hàng:</span> <strong>${order.customerName || 'Khách lẻ'}</strong></div>
        <div style="display:flex;justify-content:space-between;margin-bottom:4px"><span>Tổng tiền:</span> <strong>${formatPrice(order.totalAmount)}</strong></div>
        <div style="display:flex;justify-content:space-between"><span>Sản phẩm:</span> <strong>${(order.items || []).length} mặt hàng</strong></div>
    `;

    // Pre-fill customer info
    document.getElementById('shipReceiverName').value = order.customerName || '';
    document.getElementById('shipReceiverPhone').value = order.customerPhone || '';
    document.getElementById('shipFormFields').style.display = 'block';
}

async function submitShipForm() {
    if (!selectedOrderForShip) { alert('Vui lòng tìm đơn hàng trước'); return; }
    const name = document.getElementById('shipReceiverName').value.trim();
    const phone = document.getElementById('shipReceiverPhone').value.trim();
    const address = document.getElementById('shipReceiverAddress').value.trim();
    
    if (!name || !phone || !address) {
        alert('Vui lòng nhập đầy đủ tên, SĐT và địa chỉ người nhận');
        return;
    }

    // Phone validation: starts with 0 and total 9-10 digits
    const phoneRegex = /^0[0-9]{8,9}$/;
    if (!phoneRegex.test(phone)) {
        alert('Số điện thoại không hợp lệ! SĐT phải bắt đầu bằng số 0 và bao gồm 9 hoặc 10 chữ số.');
        return;
    }

    const submitBtn = document.getElementById('submitShipForm');
    const originalText = submitBtn.innerHTML;
    submitBtn.innerHTML = '<span class="loading-spinner"></span> Đang tạo...';
    submitBtn.disabled = true;

    // Remove retry button if it exists
    const existingRetry = document.getElementById('retryShipBtn');
    if (existingRetry) existingRetry.remove();

    try {
        const res = await fetch(`${API_BASE}/shipments`, {
            method: 'POST',
            headers: getAuthHeaders(),
            body: JSON.stringify({
                orderId: selectedOrderForShip.id,
                customerId: selectedOrderForShip.customerId,
                receiverName: name,
                receiverPhone: phone,
                receiverAddress: address,
                shippingMethod: document.getElementById('shipMethod').value,
                shippingFee: parseFloat(document.getElementById('shipFee').value) || 0,
                note: document.getElementById('shipNote').value.trim(),
                createdBy: parseInt(sessionStorage.getItem('userId')) || null
            })
        });
        if (res.ok) {
            const shipment = await res.json();
            alert(`Tạo đơn vận chuyển thành công: ${shipment.shipmentNumber}`);
            resetShipForm();
            loadShipments();
            loadStats();
            document.querySelector('.tab-btn[data-tab="list"]').click();
        } else {
            let msg = await res.text();
            try {
                const err = JSON.parse(msg);
                if (err.status === 404) msg = "Không tìm thấy dữ liệu";
                else if (err.status === 500) msg = "Lỗi server, vui lòng thử lại";
                else msg = err.message || msg;
            } catch (e) {}
            alert(`❌ Không thể tạo vận chuyển. ${msg}`);

            // Add retry button
            const retryBtn = document.createElement('button');
            retryBtn.id = 'retryShipBtn';
            retryBtn.className = 'primary-btn';
            retryBtn.style.backgroundColor = '#f59e0b';
            retryBtn.style.marginLeft = '8px';
            retryBtn.innerHTML = '🔄 Thử lại';
            retryBtn.onclick = submitShipForm;
            submitBtn.parentNode.appendChild(retryBtn);
        }
    } catch (e) { 
        alert('❌ Không thể tạo vận chuyển. Lỗi kết nối'); 
        console.error(e); 
    } finally {
        submitBtn.innerHTML = originalText;
        submitBtn.disabled = false;
    }
}

function resetShipForm() {
    selectedOrderForShip = null;
    document.getElementById('shipOrderId').value = '';
    document.getElementById('shipOrderInfo').style.display = 'none';
    document.getElementById('shipFormFields').style.display = 'none';
    document.getElementById('shipReceiverName').value = '';
    document.getElementById('shipReceiverPhone').value = '';
    document.getElementById('shipReceiverAddress').value = '';
    document.getElementById('shipNote').value = '';
    document.getElementById('shipFee').value = '0';
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
    loadShipments();

    // Search & Filter
    document.getElementById('shipSearchBtn')?.addEventListener('click', () => {
        const keyword = document.getElementById('shipSearchInput').value;
        const status = document.getElementById('shipStatusFilter').value;
        if (keyword.trim()) loadShipments(null, keyword);
        else loadShipments(status || null);
    });
    document.getElementById('shipStatusFilter')?.addEventListener('change', (e) => {
        loadShipments(e.target.value || null);
    });

    // Click handlers
    document.getElementById('shipmentsListBody')?.addEventListener('click', (e) => {
        const link = e.target.closest('.ship-link') || e.target.closest('.action-btn');
        if (link) openShipDetail(parseInt(link.dataset.id));
    });

    document.getElementById('closeShipDetail')?.addEventListener('click', () => closeModal('shipDetailModal'));
    document.getElementById('shipDetailModal')?.addEventListener('click', (e) => {
        if (e.target === e.currentTarget) closeModal('shipDetailModal');
    });

    // Create form
    document.getElementById('searchOrderForShip')?.addEventListener('click', searchOrderForShip);
    document.getElementById('shipOrderId')?.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') searchOrderForShip();
    });
    document.getElementById('submitShipForm')?.addEventListener('click', submitShipForm);
    document.getElementById('resetShipForm')?.addEventListener('click', resetShipForm);
});
