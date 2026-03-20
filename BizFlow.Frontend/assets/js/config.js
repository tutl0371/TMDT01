/**
 * API Configuration for BizFlow
 * Automatically switches between development and production
 */

const ENV = {
    development: {
        GATEWAY_URL: 'http://localhost:8000',
        ADMIN_USER_URL: 'http://localhost:8201',
        ADMIN_PRODUCT_URL: 'http://localhost:8204',
        ADMIN_ORDER_URL: 'http://localhost:8203',
        ADMIN_REPORT_URL: 'http://localhost:8205',
    },
    production: {
        // Production URLs - thay đổi khi deploy
        GATEWAY_URL: 'https://api.bizflow.com',
        ADMIN_USER_URL: 'https://api.bizflow.com/admin/users',
        ADMIN_PRODUCT_URL: 'https://api.bizflow.com/admin/products',
        ADMIN_ORDER_URL: 'https://api.bizflow.com/admin/orders',
        ADMIN_REPORT_URL: 'https://api.bizflow.com/admin/reports',
    }
};

// Auto-detect environment
const isProduction = window.location.hostname !== 'localhost' && 
                     window.location.hostname !== '127.0.0.1';

const API_CONFIG = isProduction ? ENV.production : ENV.development;

// Export for use in other files
window.API_CONFIG = API_CONFIG;

console.log('🚀 BizFlow Config loaded:', isProduction ? 'PRODUCTION' : 'DEVELOPMENT');
console.log('📍 Gateway URL:', API_CONFIG.GATEWAY_URL);
