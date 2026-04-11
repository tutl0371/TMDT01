// Shared app menu + logout for POS pages.
(() => {
    const APP_TILES = [
        { key: 'pos', label: 'Bán hàng', icon: 'BH', color: 'app-blue', href: '/pages/employee-dashboard.html' },
        { key: 'promotions', label: 'Khuyến mại', icon: 'KM', color: 'app-green', href: '/pages/promotions.html' },
        { key: 'invoices', label: 'DS hóa đơn', icon: 'HD', color: 'app-pink', href: '/pages/invoice-list.html' },
        { key: 'user-returns', label: 'Trả hàng / Hoàn tiền', icon: 'ĐR', color: 'app-orange', href: '/pages/user-returns.html' },
        { key: 'user-warranty', label: 'Tra cứu bảo hành', icon: 'BH', color: 'app-purple', href: '/pages/user-warranties.html' },
        { key: 'user-shipping', label: 'Theo dõi đơn hàng', icon: 'VC', color: 'app-orange', href: '/pages/user-shipping.html' },
        { key: 'print', label: 'Máy in - Mẫu in', icon: 'IN', color: 'app-orange', href: '/pages/print-templates.html' },
        { key: 'daily-report', label: 'Báo cáo theo ngày', icon: 'BC', color: 'app-teal', href: '/pages/daily-report.html' },
        { key: 'access-log', label: 'Nhật ký truy cập', icon: 'NK', color: 'app-gray', href: '/pages/access-log.html' },
        { key: 'management', label: 'Trang quản lý', icon: 'QL', color: 'app-indigo', href: '/pages/management.html' },
        { key: 'guide', label: 'Hướng dẫn', icon: 'HD', color: 'app-purple', href: '/pages/guide.html' },
        { key: 'intro', label: 'Giới thiệu', icon: 'GT', color: 'app-orange', href: '/pages/introduction.html' }
    ];

    function createIconButton(id, title, svg) {
        const btn = document.createElement('button');
        btn.id = id;
        btn.className = 'icon-btn';
        btn.title = title;
        btn.setAttribute('aria-label', title);
        btn.type = 'button';
        btn.innerHTML = svg;
        return btn;
    }

    function ensureHeaderButtons() {
        const headerActions = document.querySelector('.pos-header .header-actions');
        if (!headerActions) return { logoutBtn: null, appMenuBtn: null };

        let logoutBtn = document.getElementById('logoutBtn');
        if (!logoutBtn) {
            logoutBtn = createIconButton(
                'logoutBtn',
                'Đăng xuất',
                `<svg viewBox="0 0 24 24" class="icon-svg" aria-hidden="true">
                    <path d="M10 6H5v12h5" />
                    <path d="M14 16l4-4-4-4" />
                    <path d="M18 12H9" />
                </svg>`
            );
            headerActions.appendChild(logoutBtn);
        }

        let appMenuBtn = document.getElementById('appMenuBtn');
        if (!appMenuBtn) {
            appMenuBtn = createIconButton(
                'appMenuBtn',
                'Ứng dụng',
                `<svg viewBox="0 0 24 24" class="icon-svg" aria-hidden="true">
                    <circle cx="6" cy="6" r="1.5" />
                    <circle cx="12" cy="6" r="1.5" />
                    <circle cx="18" cy="6" r="1.5" />
                    <circle cx="6" cy="12" r="1.5" />
                    <circle cx="12" cy="12" r="1.5" />
                    <circle cx="18" cy="12" r="1.5" />
                </svg>`
            );
            headerActions.appendChild(appMenuBtn);
        }

        return { logoutBtn, appMenuBtn };
    }

    function buildMenuGrid() {
        return APP_TILES.map(tile => `
            <button class="app-tile" data-app="${tile.key}" data-href="${tile.href}">
                <span class="app-icon ${tile.color}">${tile.icon}</span>
                <span>${tile.label}</span>
            </button>
        `).join('');
    }

    function ensureAppMenuModal() {
        let modal = document.getElementById('appMenuModal');
        if (!modal) {
            modal = document.createElement('div');
            modal.className = 'modal side';
            modal.id = 'appMenuModal';
            modal.setAttribute('aria-hidden', 'true');
            modal.innerHTML = `
                <div class="modal-content app-menu">
                    <div class="modal-header">
                        <h3>Ứng dụng</h3>
                        <button id="closeAppMenu" class="icon-btn small" type="button" aria-label="Đóng">×</button>
                    </div>
                    <div class="app-menu-grid">
                        ${buildMenuGrid()}
                    </div>
                </div>
            `;
            document.body.appendChild(modal);
        }
        // REMOVED: Overwriting logic that replaces HTML with JS hardcoded tiles.
        // This allows developers to customize the menu in HTML directly.
        return modal;
    }

    function bindAppMenu(modal, openBtn) {
        if (!modal || !openBtn) return;
        if (!openBtn.dataset.boundAppShell) {
            openBtn.addEventListener('click', () => {
                modal.classList.add('show');
                modal.setAttribute('aria-hidden', 'false');
            });
            openBtn.dataset.boundAppShell = 'true';
        }

        const closeBtn = modal.querySelector('#closeAppMenu');
        if (closeBtn && !closeBtn.dataset.boundAppShell) {
            closeBtn.addEventListener('click', () => {
                modal.classList.remove('show');
                modal.setAttribute('aria-hidden', 'true');
            });
            closeBtn.dataset.boundAppShell = 'true';
        }

        if (!modal.dataset.boundAppShell) {
            modal.addEventListener('click', (e) => {
                if (e.target === modal) {
                    modal.classList.remove('show');
                    modal.setAttribute('aria-hidden', 'true');
                }
            });
            modal.dataset.boundAppShell = 'true';
        }

        const grid = modal.querySelector('.app-menu-grid');
        if (grid && !grid.dataset.boundAppShell) {
            grid.addEventListener('click', (e) => {
                const tile = e.target.closest('.app-tile[data-href]');
                if (!tile) return;
                const href = tile.dataset.href;
                if (href) {
                    window.location.href = href;
                }
            });
            grid.dataset.boundAppShell = 'true';
        }
    }

    function bindLogout(btn) {
        if (!btn || btn.dataset.boundAppShell) return;
        btn.addEventListener('click', () => {
            if (confirm('Đăng xuất?')) {
                sessionStorage.clear();
                window.location.href = '/pages/login.html';
            }
        });
        btn.dataset.boundAppShell = 'true';
    }

    document.addEventListener('DOMContentLoaded', () => {
        const { logoutBtn, appMenuBtn } = ensureHeaderButtons();
        const modal = ensureAppMenuModal();
        bindAppMenu(modal, appMenuBtn);
        bindLogout(logoutBtn);
        document.querySelectorAll('.logo-mark').forEach((logo) => {
            if (logo.dataset.boundAppShell) return;
            logo.style.cursor = 'pointer';
            logo.addEventListener('click', () => {
                window.location.href = '/pages/employee-dashboard.html';
            });
            logo.dataset.boundAppShell = 'true';
        });
    });
})();


