// The full card-style flow: create, authorize, capture. Each iteration is three requests. Authorize
// and capture both move money through the PAYMENT_HOLDS system account, one row per currency, so
// this test deliberately exercises the platform's known hot row.
//
// Run with performance/compose.performance.yaml: the rate limit and velocity rule would otherwise
// (correctly) refuse this traffic, and that is not what is being measured.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { API, DURATION, USERS, createWallets, headers, loadOnlyMetrics, summaryTrendStats, thinkTime } from './lib/setup.js';

const WALLETS = 200;
const flowDuration = new Trend('payment_flow_duration', true);

export const options = {
  scenarios: { load: { executor: 'constant-vus', vus: USERS, duration: DURATION } },
  summaryTrendStats,
  thresholds: loadOnlyMetrics,
  setupTimeout: '10m',
};

export function setup() {
  return { wallets: createWallets(WALLETS) };
}

export default function ({ wallets }) {
  const payer = wallets[(__VU - 1) % wallets.length];
  const merchant = wallets[__VU % wallets.length];
  const started = Date.now();

  const created = http.post(`${API}/api/v1/payments`, JSON.stringify({
    payerAccountId: payer.id,
    merchantAccountId: merchant.id,
    amount: '5.00',
    currency: 'EUR',
  }), { ...headers(payer.token), tags: { name: 'create' } });

  if (check(created, { 'payment created': (r) => r.status === 201 })) {
    const path = `${API}/api/v1/payments/${created.json('id')}`;
    const authorized = http.post(`${path}/authorize`, null, { ...headers(payer.token), tags: { name: 'authorize' } });
    if (check(authorized, { 'payment authorized': (r) => r.status === 200 })) {
      const captured = http.post(`${path}/capture`, null, { ...headers(merchant.token), tags: { name: 'capture' } });
      if (check(captured, { 'payment captured': (r) => r.status === 200 })) {
        flowDuration.add(Date.now() - started);
      }
    }
  }
  sleep(thinkTime());
}
