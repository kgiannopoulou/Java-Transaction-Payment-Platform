// Wallet-to-wallet transfers: each one locks two account rows, writes the ledger, the audit trail
// and the outbox in one transaction.
//
//   k6 run -e USERS=100 -e DURATION=60s performance/transfer-load-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { API, DURATION, USERS, createWallets, headers, summaryTrendStats, thinkTime } from './lib/setup.js';

const WALLETS = 200;

export const options = {
  scenarios: { load: { executor: 'constant-vus', vus: USERS, duration: DURATION } },
  summaryTrendStats,
  setupTimeout: '10m',
};

export function setup() {
  return { wallets: createWallets(WALLETS) };
}

export default function ({ wallets }) {
  const source = wallets[(__VU - 1) % wallets.length];
  let destination = wallets[Math.floor(Math.random() * wallets.length)];
  if (destination.id === source.id) {
    destination = wallets[(__VU % wallets.length)];
  }

  const response = http.post(`${API}/api/v1/transfers`, JSON.stringify({
    sourceAccountId: source.id,
    destinationAccountId: destination.id,
    amount: '1.00',
    currency: 'EUR',
  }), { ...headers(source.token), tags: { name: 'transfer' } });

  check(response, { 'transfer booked': (r) => r.status === 201 });
  sleep(thinkTime());
}
