// Ji-Hajoor marketplace load test: three populations hit the API gateway together.
//
//   browse     customers reading the catalog, pricing estimates and their history, with think
//              time (the bulk of real traffic; each user stays under the 100/min gateway limit)
//   customers  customers booking a plumber and paying once the job is done (the money path)
//   providers  onboarded providers polling for offers, accepting, sharing live location,
//              uploading photos and completing the job
//
// The booking journey crosses every deployable and both asynchronous hops (BookingCreated ->
// dispatch, PaymentCompleted -> booking) through Kafka, so the latency trends below measure the
// outbox relay and consumers too, not just HTTP.
//
// Needs a stack started like deploy/ci/run-stack.sh: OTP_FIXED_CODE, the payment simulator, the
// seeded catalog and pricing, and an admin account. See loadtest/README.md.
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import {
  BASE_LAT, BASE_LNG, adminSignIn, catalog, json, mobileFor, multipartPhoto, onboardProvider,
  otpSignIn, plumbing, req, waitFor,
} from './lib/api.js';

const PROFILE = __ENV.PROFILE || 'load'; // smoke | load | stress
const P = {
  smoke: { browse: 3, customers: 2, providers: 2, hold: '1m' },
  load: { browse: 60, customers: 12, providers: 8, hold: '4m' },
  stress: { browse: 150, customers: 30, providers: 12, hold: '5m' },
}[PROFILE];
const BROWSE_VUS = Number(__ENV.BROWSE_VUS || P.browse);
const CUSTOMER_VUS = Number(__ENV.CUSTOMER_VUS || P.customers);
const PROVIDERS = Number(__ENV.PROVIDERS || P.providers);
const HOLD = __ENV.HOLD || P.hold;
// Fixed once in setup() and handed to every VU: each VU runs its init code at its own moment,
// so a clock-based id computed here would differ between VUs.
const RUN_ID_ENV = __ENV.RUN_ID;

// 409 is an offer another provider in the same batch took first: an expected outcome.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 409));

const acceptLatency = new Trend('journey_confirm_to_accepted_ms', true);
const offerPickup = new Trend('journey_offer_pickup_ms', true);
const paymentSettle = new Trend('journey_payment_to_completed_ms', true);
const journeyTotal = new Trend('journey_total_ms', true);
const journeysDone = new Counter('journeys_completed');
const journeysFailed = new Counter('journeys_failed');
const searchFailed = new Counter('journeys_search_failed');
const rateLimited = new Counter('http_429');

export const options = {
  setupTimeout: '10m',
  scenarios: {
    providers: {
      executor: 'constant-vus', exec: 'provider', vus: PROVIDERS,
      duration: addDurations('30s', HOLD, '2m'),
    },
    customers: {
      executor: 'ramping-vus', exec: 'customer', startTime: '10s', startVUs: 0,
      stages: [{ duration: '30s', target: CUSTOMER_VUS }, { duration: HOLD, target: CUSTOMER_VUS }, { duration: '20s', target: 0 }],
      gracefulRampDown: '3m', gracefulStop: '3m',
    },
    browse: {
      executor: 'ramping-vus', exec: 'browse', startTime: '10s', startVUs: 0,
      stages: [{ duration: '1m', target: BROWSE_VUS }, { duration: HOLD, target: BROWSE_VUS }, { duration: '20s', target: 0 }],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    // Unexpected statuses (anything but 2xx/3xx, 409 on a taken offer, 404 on an empty location).
    'http_req_failed': ['rate<0.01'],
    'checks': ['rate>0.98'],
    'http_req_duration{scenario:browse}': ['p(95)<500', 'p(99)<1500'],
    'http_req_duration{name:POST /bookings}': ['p(95)<1500'],
    'http_req_duration{name:POST /bookings/{ref}/confirmation}': ['p(95)<1500'],
    'http_req_duration{name:POST /locations/{id}}': ['p(95)<300'],
    'http_req_duration{name:POST /payments}': ['p(95)<2000'],
    'journey_confirm_to_accepted_ms': ['p(95)<30000'],
    'journey_payment_to_completed_ms': ['p(95)<15000'],
    'journeys_failed': ['count<1'],
    'http_429': ['count<1'],
    // Sub-metrics only so the summary names the limited endpoints (always pass).
    'http_429{endpoint:/dispatch/offers}': ['count>=0'],
    'http_429{endpoint:/bookings/{id}}': ['count>=0'],
    'http_429{endpoint:/catalog/categories}': ['count>=0'],
    'http_429{endpoint:/pricing/estimate}': ['count>=0'],
    'http_429{endpoint:/bookings/history}': ['count>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function addDurations(...ds) {
  const s = ds.reduce((acc, d) => acc + (d.endsWith('m') ? parseFloat(d) * 60 : parseFloat(d)), 0);
  return `${Math.ceil(s)}s`;
}

// Path of a request with ids and the query removed, e.g. /bookings/{id}.
function endpointOf(res) {
  const url = res.request && res.request.url ? String(res.request.url) : '';
  return url.replace(/^https?:\/\/[^/]+/, '').replace(/\?.*$/, '').replace(/[0-9a-f-]{36}/g, '{id}');
}

function track(res) {
  if (res && res.status === 429) {
    rateLimited.add(1, { endpoint: endpointOf(res) });
    console.warn(`429 on ${res.request.method} ${endpointOf(res)} (Retry-After ${res.headers['Retry-After']})`);
  }
  return res;
}

// ---------------------------------------------------------------- setup: providers on the market

export function setup() {
  const runId = Number(RUN_ID_ENV || Math.floor(Date.now() / 1000) % 1000);
  const admin = adminSignIn();
  const providers = [];
  for (let i = 0; i < PROVIDERS; i++) {
    providers.push(onboardProvider(mobileFor(runId, 1, i), admin, i));
  }
  const { cat, sub } = plumbing(catalog(admin));
  console.log(`setup: run ${runId}, profile ${PROFILE}: ${PROVIDERS} providers approved; ` +
    `${CUSTOMER_VUS} booking customers, ${BROWSE_VUS} browsing customers`);
  return { runId, providers, categoryId: cat.id, subcategoryId: sub.id };
}

// Take this run's providers off the market again: an approved provider who no longer answers
// would otherwise take the first offer batch of every later booking for a whole offer window.
export function teardown(data) {
  const admin = adminSignIn();
  for (const p of data.providers) {
    const r = req('PATCH', `/admin/providers/${p.userId}/status`, { status: 'SUSPENDED' }, admin,
      'PATCH /admin/providers/{id}/status');
    if (r.status !== 200) console.warn(`teardown: provider ${p.userId} not suspended: HTTP ${r.status}`);
  }
}

// ---------------------------------------------------------------- per-VU session

let session = null; // { token, userId, addressId }

function customerSession(data, scenarioDigit) {
  if (session) return session;
  const s = otpSignIn(mobileFor(data.runId, scenarioDigit, exec.vu.idInTest), 'CUSTOMER');
  if (!s) return null;
  const a = track(req('POST', `/customers/${s.userId}/addresses`,
    { label: 'Home', lat: BASE_LAT + Math.random() * 0.004, lng: BASE_LNG + Math.random() * 0.004 },
    s.token, 'POST /customers/{id}/addresses'));
  const addr = json(a);
  if (!check(a, { 'address saved': () => !!(addr && addr.addressId) })) return null;
  session = Object.assign(s, { addressId: addr.addressId });
  return session;
}

// ---------------------------------------------------------------- browse

export function browse(data) {
  const s = customerSession(data, 2);
  if (!s) { sleep(5); return; }
  group('browse', () => {
    const c = track(req('GET', '/catalog/categories', undefined, s.token, 'GET /catalog/categories'));
    check(c, { 'catalog 200': (r) => r.status === 200 });
    sleep(1 + Math.random());
    const e = track(req('POST', '/pricing/estimate', { subcategoryId: data.subcategoryId, emergency: Math.random() < 0.1 },
      s.token, 'POST /pricing/estimate'));
    check(e, { 'estimate 200': (r) => r.status === 200 });
    sleep(1 + Math.random());
    const h = track(req('GET', '/bookings/history?page=1&pageSize=20', undefined, s.token, 'GET /bookings/history'));
    check(h, { 'history 200': (r) => r.status === 200 });
  });
  sleep(2 + Math.random() * 2);
}

// ---------------------------------------------------------------- customer: book, wait, pay

function bookingStatus(token, bookingId) {
  const r = track(req('GET', `/bookings/${bookingId}`, undefined, token, 'GET /bookings/{id}'));
  const b = json(r);
  return b ? b.status : null;
}

export function customer(data) {
  const s = customerSession(data, 3);
  if (!s) { journeysFailed.add(1); sleep(5); return; }
  const started = Date.now();

  const at = new Date(Date.now() + 6 * 3600 * 1000).toISOString().replace(/\.\d+Z$/, 'Z');
  const created = track(req('POST', '/bookings', {
    addressId: s.addressId, categoryId: data.categoryId, subcategoryId: data.subcategoryId,
    emergency: false, scheduledAt: at, description: `load test ${data.runId}/${exec.vu.idInTest}/${exec.vu.iterationInScenario}`,
  }, s.token, 'POST /bookings'));
  const b = json(created);
  if (!check(created, { 'booking created': () => !!(b && b.reference) })) { journeysFailed.add(1); sleep(5); return; }
  const bookingId = b.id || b.bookingId;
  const conf = track(req('POST', `/bookings/${b.reference}/confirmation`, undefined, s.token, 'POST /bookings/{ref}/confirmation'));
  if (!check(conf, { 'booking confirmed': (r) => r.status === 200 })) { journeysFailed.add(1); sleep(5); return; }
  const confirmedAt = Date.now();

  // Dispatch (via Kafka) offers it; a provider VU (polling every 5 s) accepts it.
  const accepted = waitFor(() => {
    const st = bookingStatus(s.token, bookingId);
    if (st === 'SEARCHING_FAILED' || st === 'AWAITING_ASSIGNMENT') return st;
    return st && st !== 'CREATED' && st !== 'SEARCHING_PROVIDER' ? st : null;
  }, 240, 2);
  if (accepted === 'SEARCHING_FAILED' || accepted === 'AWAITING_ASSIGNMENT') {
    searchFailed.add(1); journeysFailed.add(1);
    console.warn(`booking ${b.reference}: search ended ${accepted}`);
    return;
  }
  if (!check(accepted, { 'provider accepted within 240 s': (v) => !!v })) { journeysFailed.add(1); return; }
  acceptLatency.add(Date.now() - confirmedAt);

  // While the job runs the customer watches the live location now and then.
  const done = waitFor(() => {
    const st = bookingStatus(s.token, bookingId);
    if (st === 'PROVIDER_ON_THE_WAY') track(req('GET', `/locations/${bookingId}`, undefined, s.token, 'GET /locations/{id}',
      { responseCallback: http.expectedStatuses(200, 404) }));
    return st === 'JOB_COMPLETED' || st === 'CUSTOMER_CONFIRMED' || st === 'PAYMENT_PENDING' ? st : null;
  }, 240, 3);
  if (!check(done, { 'job completed within 240 s': (v) => !!v })) { journeysFailed.add(1); return; }

  const pay = track(req('POST', '/payments', { bookingId, method: 'UPI' }, s.token, 'POST /payments'));
  if (!check(pay, { 'payment accepted': (r) => r.status === 200 || r.status === 201 })) {
    console.warn(`payment for ${b.reference}: HTTP ${pay.status} ${String(pay.body).slice(0, 200)}`);
    journeysFailed.add(1); return;
  }
  const paidAt = Date.now();
  const settled = waitFor(() => bookingStatus(s.token, bookingId) === 'PAYMENT_COMPLETED', 90, 1);
  if (!check(settled, { 'PAYMENT_COMPLETED within 90 s': (v) => !!v })) { journeysFailed.add(1); return; }
  paymentSettle.add(Date.now() - paidAt);
  journeyTotal.add(Date.now() - started);
  journeysDone.add(1);
  sleep(5 + Math.random() * 5);
}

// ---------------------------------------------------------------- provider: take and do jobs

export function provider(data) {
  const me = data.providers[(exec.vu.idInTest - 1) % data.providers.length];
  const t = me.token;
  const r = track(req('GET', '/dispatch/offers', undefined, t, 'GET /dispatch/offers'));
  const list = json(r);
  // Back off on anything but a list (a 429 or 5xx body): an iteration must never spin.
  if (!check(r, { 'offers 200': (x) => x.status === 200 && Array.isArray(list) })) { sleep(5); return; }
  const offers = list.filter((o) => o.status === 'PENDING' && o.expiresInSeconds > 1);
  // The provider app polls every 5 s (PENDING_OFFERS_POLL_MS); providers get 60 requests/min.
  if (offers.length === 0) { sleep(5); return; }

  const offer = offers[0];
  if (offer.offeredAt) offerPickup.add(Date.now() - Date.parse(offer.offeredAt));
  const acc = track(req('POST', `/dispatch/offers/${offer.bookingId}/accept`, undefined, t, 'POST /dispatch/offers/{id}/accept'));
  // 409 = another provider in the same batch took it first: expected, not a failure.
  if (acc.status === 409) { sleep(1); return; }
  if (!check(acc, { 'offer accepted': (x) => x.status === 200 })) { sleep(2); return; }

  // Booking-service learns of the acceptance asynchronously; wait until the job is ours.
  const B = `/bookings/${offer.bookingId}`;
  const ours = waitFor(() => {
    const d = json(track(req('GET', B, undefined, t, 'GET /bookings/{id}')));
    return d && d.status === 'PROVIDER_ACCEPTED';
  }, 60, 2);
  if (!check(ours, { 'booking shows PROVIDER_ACCEPTED': (v) => !!v })) return;

  const step = (path, name) => {
    const x = track(req('POST', `${B}/${path}`, undefined, t, name));
    check(x, { [`${path} 200`]: (y) => y.status === 200 });
  };
  group('job', () => {
    step('on-the-way', 'POST /bookings/{id}/on-the-way');
    // Every 5 s, as the provider app sends it: location-service accepts one update per
    // provider per 5 s (Requirement 10.1) and answers 429 LOCATION_UPDATE_RATE_LIMITED sooner.
    for (let i = 0; i < 3; i++) {
      const l = track(req('POST', `/locations/${offer.bookingId}`,
        { latitude: BASE_LAT + i * 0.0005, longitude: BASE_LNG + i * 0.0005 }, t, 'POST /locations/{id}'));
      check(l, { 'location 202': (y) => y.status === 202 });
      sleep(5.2);
    }
    step('arrived', 'POST /bookings/{id}/arrived');
    check(multipartPhoto(`${B}/photos`, t, 'BEFORE_PHOTO'), { 'before photo 204': (y) => y.status === 204 });
    step('start', 'POST /bookings/{id}/start');
    sleep(2);
    check(multipartPhoto(`${B}/photos`, t, 'AFTER_PHOTO'), { 'after photo 204': (y) => y.status === 204 });
    step('complete', 'POST /bookings/{id}/complete');
  });
  sleep(1);
}

export function handleSummary(data) {
  const out = __ENV.SUMMARY_DIR || '/results';
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    [`${out}/marketplace-${PROFILE}-${stamp}.json`]: JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

// A compact plain-text summary (k6's built-in one is long with many tagged series).
function textSummary(data) {
  const m = data.metrics;
  const lines = [`\nJi-Hajoor marketplace load test (${PROFILE})`];
  const fmt = (v) => (v === undefined ? '-' : v >= 1000 ? `${(v / 1000).toFixed(2)}s` : `${v.toFixed(0)}ms`);
  for (const [k, v] of Object.entries(m).sort()) {
    if (v.type === 'trend' && (k.startsWith('journey_') || k.startsWith('http_req_duration{'))) {
      lines.push(`  ${k.padEnd(62)} p50 ${fmt(v.values.med).padStart(8)}  p95 ${fmt(v.values['p(95)']).padStart(8)}  p99 ${fmt(v.values['p(99)']).padStart(8)}  max ${fmt(v.values.max).padStart(8)}`);
    }
  }
  const c = (k) => (m[k] ? m[k].values.count : 0);
  lines.push(`  requests ${c('http_reqs')} (${(m.http_reqs ? m.http_reqs.values.rate : 0).toFixed(1)}/s), ` +
    `failed ${((m.http_req_failed ? m.http_req_failed.values.rate : 0) * 100).toFixed(2)}%, ` +
    `checks ${((m.checks ? m.checks.values.rate : 0) * 100).toFixed(2)}%`);
  for (const [k, v] of Object.entries(m)) {
    if (k.startsWith('http_429{')) lines.push(`  ${k}: ${v.values.count}`);
  }
  lines.push(`  journeys completed ${c('journeys_completed')}, failed ${c('journeys_failed')} ` +
    `(search failed ${c('journeys_search_failed')}), 429s ${c('http_429')}`);
  const failed = Object.entries(m).filter(([, v]) => v.thresholds && Object.values(v.thresholds).some((t) => !t.ok));
  lines.push(failed.length ? `  THRESHOLDS FAILED: ${failed.map(([k]) => k).join(', ')}` : '  all thresholds passed');
  return lines.join('\n') + '\n';
}
