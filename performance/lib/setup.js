// Shared fixtures for the load tests: customers, funded wallets and tokens. Runs once, before the
// measured part, through the dev token endpoint (local and test environments only).
import http from 'k6/http';
import { check } from 'k6';

export const API = __ENV.API_URL || 'http://localhost:8080';
export const USERS = parseInt(__ENV.USERS || '50');
export const DURATION = __ENV.DURATION || '60s';

// p50 is "med". Thresholds are deliberately absent: these tests measure, they do not pass or fail.
export const summaryTrendStats = ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'];

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function post(path, body, token) {
  const headers = token ? { ...JSON_HEADERS, Authorization: `Bearer ${token}` } : JSON_HEADERS;
  const response = http.post(`${API}${path}`, JSON.stringify(body), { headers, tags: { name: 'setup' } });
  check(response, { 'setup call succeeded': (r) => r.status >= 200 && r.status < 300 });
  if (response.status >= 300) {
    throw new Error(`setup failed: POST ${path} -> ${response.status} ${response.body}`);
  }
  return response.json();
}

export function headers(token) {
  return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } };
}

/** Wallets with an owner's token each. Balances are large enough never to run out during a run. */
export function createWallets(count) {
  const admin = post('/api/v1/auth/token', { subject: 'load-test', roles: ['ADMIN'] }).accessToken;
  const run = Date.now();
  const wallets = [];
  for (let i = 0; i < count; i++) {
    const customer = post('/api/v1/customers', { email: `load-${run}-${i}@example.com`, fullName: 'Load Test' }, admin).id;
    const token = post('/api/v1/auth/token', { subject: customer, roles: ['CUSTOMER'], customerId: customer }).accessToken;
    const wallet = post('/api/v1/accounts', { customerId: customer, currency: 'EUR' }, token).id;
    post(`/api/v1/accounts/${wallet}/deposits`, { amount: '100000000.00', currency: 'EUR' }, admin);
    wallets.push({ id: wallet, token });
  }
  return wallets;
}

/** A pause between actions, so a virtual user behaves like a client, not a tight loop. */
export function thinkTime() {
  return 0.5 + Math.random();
}
