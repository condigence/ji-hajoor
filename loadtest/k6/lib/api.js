// Shared client for the Ji-Hajoor k6 scripts: talks to the API gateway exactly as the apps do
// (bearer token, JSON, X-Forwarded-Proto: https because the gateway enforces HTTPS behind an edge).
import http from 'k6/http';
import { check, fail, sleep } from 'k6';

export const GATEWAY = __ENV.GATEWAY || 'http://localhost:8080';
export const OTP = __ENV.OTP || '123456';
export const ADMIN_USER = __ENV.ADMIN_USER || 'owner';
export const ADMIN_PASS = __ENV.ADMIN_PASS || 'ci-admin-password-1';

// Around the seeded Ara service area (deploy/ci/booking-flow.sh uses the same point).
export const BASE_LAT = 25.5571;
export const BASE_LNG = 84.6612;

// A 1x1 JPEG: enough for the verification and job-photo uploads.
const JPEG_B64 =
  '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==';
import encoding from 'k6/encoding';
export const JPEG = encoding.b64decode(JPEG_B64, 'std');

const BASE_HEADERS = { 'X-Forwarded-Proto': 'https' };

function headers(token, extra) {
  const h = Object.assign({}, BASE_HEADERS, extra || {});
  if (token) h.Authorization = `Bearer ${token}`;
  return h;
}

/** JSON request. `name` groups URLs with ids in them into one metric series. */
export function req(method, path, body, token, name, extraParams) {
  const params = Object.assign(
    { headers: headers(token, body === undefined ? {} : { 'Content-Type': 'application/json' }), tags: { name: name || path } },
    extraParams || {},
  );
  return http.request(method, `${GATEWAY}${path}`, body === undefined ? null : JSON.stringify(body), params);
}

export function json(res) {
  try {
    return res.json();
  } catch (_) {
    return null;
  }
}

/**
 * A mobile number unique to this run, scenario and VU: +91 then ten digits starting 6-9.
 * The run id changes every second, so reruns do not hit the 5-per-hour OTP throttle.
 */
export function mobileFor(runId, scenarioDigit, n) {
  return `+916${String(runId).padStart(3, '0').slice(-3)}${scenarioDigit}${String(n).padStart(5, '0').slice(-5)}`;
}

/** Mobile OTP sign-in (fixed test code), the customer and provider apps' main path. */
export function otpSignIn(mobile, role) {
  const r1 = req('POST', '/auth/register/otp', { mobileNumber: mobile, role }, null, 'POST /auth/register/otp');
  if (!check(r1, { 'otp requested (202)': (r) => r.status === 202 })) return null;
  const r2 = req('POST', '/auth/register/verify', { mobileNumber: mobile, otp: OTP }, null, 'POST /auth/register/verify');
  const s = json(r2);
  if (!check(r2, { 'otp verified (token)': () => !!(s && s.accessToken) })) return null;
  return { token: s.accessToken, userId: s.userId, mobile };
}

export function adminSignIn() {
  const r = req('POST', '/auth/login/password', { username: ADMIN_USER, password: ADMIN_PASS }, null, 'POST /auth/login/password');
  const s = json(r);
  if (!s || !s.accessToken) fail(`admin sign-in failed: HTTP ${r.status} ${String(r.body).slice(0, 200)}`);
  return s.accessToken;
}

export function catalog(token) {
  const r = req('GET', '/catalog/categories', undefined, token, 'GET /catalog/categories');
  return json(r) || [];
}

/** Plumbing > Tap / Faucet Repair, which the seed always creates. */
export function plumbing(categories) {
  const cat = categories.find((c) => c.name === 'Plumbing');
  if (!cat) fail('catalog has no Plumbing category; run the seed first');
  const sub = cat.subcategories.find((s) => s.name === 'Tap / Faucet Repair') || cat.subcategories[0];
  return { cat, sub };
}

/**
 * Onboards one provider end to end, as booking-flow.sh does: OTP sign-up, profile with the
 * Plumbing skills near the base point, documents, admin approval and a passed background check.
 */
export function onboardProvider(mobile, adminToken, i) {
  const s = otpSignIn(mobile, 'SERVICE_PROVIDER');
  if (!s) fail(`provider ${mobile} could not sign in`);
  const { cat } = plumbing(catalog(s.token));
  const skillTags = [...new Set(cat.subcategories.flatMap((x) => x.skillTags || []))];
  const profile = {
    displayName: `Load Plumber ${i}`,
    yearsExperience: 5,
    serviceRadiusKm: 15,
    // Spread providers a few hundred metres apart so ranking has something to rank.
    baseLatitude: BASE_LAT + (i % 5) * 0.002,
    baseLongitude: BASE_LNG + Math.floor(i / 5) * 0.002,
    categories: [{ categoryId: cat.id, subcategoryIds: cat.subcategories.map((x) => x.id) }],
    skillTags,
  };
  const p = req('PUT', '/providers/me/profile', profile, s.token, 'PUT /providers/me/profile');
  if (p.status !== 200) fail(`provider profile: HTTP ${p.status} ${String(p.body).slice(0, 200)}`);

  const docs = {
    GOVERNMENT_ID: http.file(JPEG, 'id.jpg', 'image/jpeg'),
    ADDRESS_PROOF: http.file(JPEG, 'address.jpg', 'image/jpeg'),
    SKILL_CERTIFICATION: http.file(JPEG, 'cert.jpg', 'image/jpeg'),
  };
  const d = http.post(`${GATEWAY}/verifications/${s.userId}/documents`, docs, {
    headers: headers(s.token),
    tags: { name: 'POST /verifications/{id}/documents' },
  });
  if (d.status !== 200 && d.status !== 201) fail(`documents: HTTP ${d.status} ${String(d.body).slice(0, 200)}`);

  const a = req('POST', `/admin/verification/${s.userId}/decision`, { decision: 'APPROVE' }, adminToken,
    'POST /admin/verification/{id}/decision');
  if (a.status !== 204) fail(`approve: HTTP ${a.status} ${String(a.body).slice(0, 200)}`);
  const b = req('POST', `/admin/verification/${s.userId}/background-check`,
    { outcome: 'PASSED', result: 'load test: identity and records checked' }, adminToken,
    'POST /admin/verification/{id}/background-check');
  if (b.status !== 204) fail(`background check: HTTP ${b.status} ${String(b.body).slice(0, 200)}`);
  return s;
}

/** Polls `fn` every `intervalS` until it returns a truthy value or `timeoutS` passes. */
export function waitFor(fn, timeoutS, intervalS) {
  const deadline = Date.now() + timeoutS * 1000;
  while (Date.now() < deadline) {
    const v = fn();
    if (v) return v;
    sleep(intervalS);
  }
  return null;
}

export function multipartPhoto(path, token, type) {
  return http.post(`${GATEWAY}${path}`, { type, file: http.file(JPEG, `${type}.jpg`, 'image/jpeg') }, {
    headers: headers(token),
    tags: { name: 'POST /bookings/{id}/photos' },
  });
}
