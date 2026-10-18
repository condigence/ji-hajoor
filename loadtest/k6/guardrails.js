// Checks that the gateway's protective limits hold under a burst (Requirements 23.3, 23.4):
//
//   per-user limit  one customer firing well over 100 requests a minute gets 429 + Retry-After,
//                   and another customer is unaffected at the same time
//   OTP throttle    the 6th code request for one number within the hour gets 429
//   no token        a protected route answers 401, never 5xx, under concurrency
//
// Short (well under a minute) and safe to run right after marketplace.js.
import http from 'k6/http';
import { check } from 'k6';
import { json, mobileFor, otpSignIn, req } from './lib/api.js';

const RUN_ID = Number(__ENV.RUN_ID || Math.floor(Date.now() / 1000) % 1000);
// Every status here is one the check asserts on; none is a transport failure.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

export const options = {
  scenarios: {
    burst: { executor: 'per-vu-iterations', exec: 'burst', vus: 1, iterations: 1, maxDuration: '2m' },
    otp: { executor: 'per-vu-iterations', exec: 'otp', vus: 1, iterations: 1, maxDuration: '1m' },
    anonymous: { executor: 'constant-arrival-rate', exec: 'anonymous', rate: 50, timeUnit: '1s', duration: '20s', preAllocatedVUs: 20 },
  },
  thresholds: {
    'checks': ['rate==1.0'],
    'http_req_failed': ['rate==0'],
  },
};

export function burst() {
  const noisy = otpSignIn(mobileFor(RUN_ID, 7, 1), 'CUSTOMER');
  const quiet = otpSignIn(mobileFor(RUN_ID, 7, 2), 'CUSTOMER');
  check(noisy && quiet, { 'both customers signed in': (v) => !!v });
  if (!noisy || !quiet) return;

  // 130 requests in a few seconds: the first 100 pass, the rest are limited.
  const batch = [];
  for (let i = 0; i < 130; i++) {
    batch.push(['GET', `${__ENV.GATEWAY || 'http://localhost:8080'}/catalog/categories`, null,
      { headers: { 'X-Forwarded-Proto': 'https', Authorization: `Bearer ${noisy.token}` }, tags: { name: 'burst catalog' } }]);
  }
  const rs = http.batch(batch);
  const ok = rs.filter((r) => r.status === 200).length;
  const limited = rs.filter((r) => r.status === 429);
  console.log(`burst: ${ok} x 200, ${limited.length} x 429, other ${rs.length - ok - limited.length}`);
  check(null, {
    'per-user limit engages (some 429)': () => limited.length > 0,
    'at most 100 pass in the minute': () => ok <= 100,
    'nothing but 200 or 429': () => ok + limited.length === rs.length,
    '429 carries Retry-After': () => limited.length > 0 && !!limited[0].headers['Retry-After'],
  });
  const other = req('GET', '/catalog/categories', undefined, quiet.token, 'quiet catalog');
  check(other, { 'another customer is not limited': (r) => r.status === 200 });
}

export function otp() {
  const mobile = mobileFor(RUN_ID, 8, 1);
  const codes = [];
  for (let i = 0; i < 6; i++) {
    codes.push(req('POST', '/auth/register/otp', { mobileNumber: mobile, role: 'CUSTOMER' }, null, 'otp throttle').status);
  }
  console.log(`otp throttle: ${codes.join(' ')}`);
  check(codes, {
    'first five codes accepted': (c) => c.slice(0, 5).every((x) => x === 202),
    'sixth code in the hour refused with 429': (c) => c[5] === 429,
  });
}

export function anonymous() {
  const r = req('GET', '/bookings/history', undefined, null, 'anonymous history');
  check(r, { 'no token -> 401': (x) => x.status === 401 });
  const body = json(r);
  check(body, { '401 has an error body': (b) => b !== null });
}
