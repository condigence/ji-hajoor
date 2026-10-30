// Builds the Ji-Hajoor business decks into docs/:
//   JiHajoor_Pitch_Deck.pptx, JiHajoor_Revenue_Model.pptx,
//   JiHajoor_Tech_Stack.pptx, JiHajoor_Brand_and_Marketing.pptx
// Usage (from the repository root): PPTX_SKILL=<pptx skill dir> node docs/source/build_decks.js
const path = require('path');
const fa = require('react-icons/fa');
const L = require('./lib');

const OUT = path.join(__dirname, '..');
const MARKET_SRC = 'Source: Redseer Strategy Consultants via Outlook Business (Sept 2025); FY25 figures, FY30 projection.';

async function titleSlide(pres, C, section, title, subtitle, meta, Icon, notes) {
  const s = pres.addSlide({ masterName: 'JH_TITLE', sectionTitle: section });
  s.addText(title, { placeholder: 'title' });
  s.addText(subtitle, { placeholder: 'subtitle' });
  s.addText(meta, { placeholder: 'meta' });
  s.addShape(pres.shapes.OVAL, { x: 6.85, y: 1.15, w: 2.6, h: 2.6, fill: { color: C.accent1 },
    line: { color: C.accent1 }, objectName: 'hero-circle' });
  s.addImage({ data: await L.icon(Icon, 'FFFFFF'), x: 7.5, y: 1.8, w: 1.3, h: 1.3, objectName: 'hero-icon' });
  s.addShape(pres.shapes.OVAL, { x: 8.75, y: 3.35, w: 0.7, h: 0.7, fill: { color: C.accent2 },
    line: { color: C.accent2 }, objectName: 'hero-dot' });
  s.addImage({ data: await L.icon(fa.FaCheck, '0F2A6E'), x: 8.93, y: 3.53, w: 0.34, h: 0.34,
    objectName: 'hero-check' });
  if (notes) s.addNotes(notes);
}

function sectionSlide(pres, section, kicker, title, body) {
  const s = pres.addSlide({ masterName: 'JH_SECTION', sectionTitle: section });
  s.addText(kicker, { placeholder: 'kicker' });
  s.addText(title, { placeholder: 'title' });
  if (body) s.addText(body, { placeholder: 'body' });
  return s;
}

function closing(pres, section, title, lines, notes) {
  const s = pres.addSlide({ masterName: 'JH_CLOSING', sectionTitle: section });
  s.addText(title, { placeholder: 'title' });
  s.addText(lines.map((t, i) => ({ text: t, options: { breakLine: i < lines.length - 1,
    paraSpaceAfter: 8 } })), { placeholder: 'body' });
  if (notes) s.addNotes(notes);
}

// Worked price example shared by the pitch and revenue decks.
function priceTable(slide, C, opts) {
  L.table(slide, C, [
    ['Scheduled tap repair', { text: 'Amount', options: { align: 'right' } }, 'Goes to'],
    ['Service charges', { text: '₹299.00', options: { align: 'right' } }, 'Worker'],
    ['Platform fee (10%)', { text: '₹29.90', options: { align: 'right', bold: true } }, { text: 'Ji-Hajoor', options: { bold: true } }],
    ['GST 18% on ₹328.90', { text: '₹59.20', options: { align: 'right' } }, 'Government'],
    [{ text: 'Customer pays', options: { bold: true } }, { text: '₹388.10', options: { align: 'right', bold: true } }, ''],
  ], opts);
}

// =====================================================================================
async function pitch() {
  const { pres, C } = L.newDeck('Ji-Hajoor — Pitch Deck');
  pres.addSection({ title: 'Opening' });
  await titleSlide(pres, C, 'Opening', 'Ji-Hajoor', 'Verified Help. Anytime. Anywhere.',
    'Pitch deck · October 2026 · Powered by Condigence LLP', fa.FaHome,
    'Ji-Hajoor is a home-services marketplace that connects households with verified professionals, with the price shown before booking and reviews that customers can trust.');

  pres.addSection({ title: 'Problem and solution' });
  let s = L.content(pres, 'Problem and solution', 'Finding a trustworthy pro is still a gamble',
    'Most home repairs are still booked by word of mouth: no checks, no price list, no recourse',
    'Three pains we hear from every household: who is this person, what will it really cost, and will they turn up.');
  await L.cardsRow(pres, s, C, [
    { Icon: fa.FaUserShield, title: 'Trust', body: 'A stranger in your home with no ID check, no history and no one to call if it goes wrong.' },
    { Icon: fa.FaRupeeSign, title: 'Surprise prices', body: 'The quote changes at the door, and parts are added without asking.', color: C.accent2 },
    { Icon: fa.FaClock, title: 'No-shows', body: 'Late or missed visits, with no way to see who is coming or when.', color: C.accent3 },
  ], { y: 1.6, h: 2.55 });
  L.callout(pres, s, C, 'Under 1% of India’s ₹5.1 lakh crore home-services market is booked online (Redseer, FY25).');

  s = L.content(pres, 'Problem and solution', 'One marketplace, three apps, built on trust',
    'Each side of the market gets an app designed for its job',
    'Customer app at jihajoor.com, Ji-Hajoor Pro for workers at partner.jihajoor.com, and Ji-Hajoor Admin for staff and agencies.');
  await L.cardsRow(pres, s, C, [
    { Icon: fa.FaHome, kicker: 'JIHAJOOR.COM', title: 'Ji-Hajoor', body: 'For customers: book in two minutes, see the price first, track and chat live, pay and rate.' },
    { Icon: fa.FaTools, kicker: 'PARTNER.JIHAJOOR.COM', title: 'Ji-Hajoor Pro', body: 'For workers: get verified, take jobs nearby, record photos and parts, get paid, build reviews.', color: C.accent3 },
    { Icon: fa.FaChartLine, kicker: 'ADMIN.JIHAJOOR.COM', title: 'Ji-Hajoor Admin', body: 'For staff and agencies: verification, pricing, dispatch, refunds, moderation; My agency for teams.', color: C.text2 },
  ], { y: 1.6, h: 3.35 });

  s = L.content(pres, 'Problem and solution', 'How it works', 'From a tap on Book to a public review',
    'Five steps. Every step leaves a record the customer and the worker can see.');
  L.flow(pres, s, C, [
    { title: 'Book', body: 'Pick a service, place and time. See the itemised price.' },
    { title: 'Match', body: 'Nearby verified pros get the offer. First to accept wins.' },
    { title: 'Track', body: 'Live map and chat. The customer approves any parts.' },
    { title: 'Pay', body: 'UPI, card, net banking or wallet. GST invoice at once.' },
    { title: 'Rate', body: 'Asked after paying, one reminder. Public review, worker replies.' },
  ]);
  L.callout(pres, s, C, 'Every step leaves a record both sides can see: the estimate, chat, job photos, the invoice and the review.', { y: 4.35, h: 0.6, fill: 'EEF2FD', color: '0F2A6E' });

  s = L.content(pres, 'Problem and solution', 'Trust is the product', 'Four guarantees every booking carries',
    'These are not marketing claims: each one is enforced by the platform.');
  await L.iconGrid(pres, s, C, [
    { Icon: fa.FaIdCard, title: 'Verified professionals', body: 'Government ID, address proof and skill certificate checked, then a background check.' },
    { Icon: fa.FaFileInvoice, title: 'Price before you book', body: 'An itemised estimate up front. Parts need the customer’s approval.', color: C.accent2 },
    { Icon: fa.FaMapMarkedAlt, title: 'Live and accountable', body: 'Tracking, chat, and complaints answered by a real person, with refunds.', color: C.accent3 },
    { Icon: fa.FaStar, title: 'Honest reviews', body: 'Only from paid jobs, fraud-checked, anonymous, with the worker’s reply.', color: C.accent4 },
  ]);

  s = L.content(pres, 'Problem and solution', 'Two supply channels, so jobs get done',
    'Independent workers first; agencies catch whatever is left',
    'Dispatch offers each job to the best-matched independent workers. If nobody accepts in time, it waits in the queue of agencies covering that area, and an agency assigns one of its verified workers.');
  L.box(pres, s, C, { x: 0.5, y: 2.45, w: 1.9, h: 0.9, text: 'Customer request', fill: C.text2, color: C.background1 });
  L.arrow(pres, s, { x: 2.45, y: 2.9, w: 0.6, h: 0 });
  L.box(pres, s, C, { x: 3.1, y: 2.45, w: 2.0, h: 0.9, text: 'Dispatch engine', fill: C.accent1, color: C.background1 });
  L.arrow(pres, s, { x: 5.15, y: 2.6, w: 0.7, h: 0.6, flipV: true });
  L.arrow(pres, s, { x: 5.15, y: 3.2, w: 0.7, h: 0.6 });
  await L.card(pres, s, C, { x: 5.9, y: 1.55, w: 3.6, h: 1.55, title: 'Independent workers',
    body: 'Offered first, by distance, rating and track record. First to accept wins.', titleSize: 16, bodySize: 14 });
  await L.card(pres, s, C, { x: 5.9, y: 3.35, w: 3.6, h: 1.55, title: 'Agency queue',
    body: 'No one in time? The covering agency assigns a verified team member.', titleSize: 16, bodySize: 14 });
  s.addText('Agencies keep a 0–50% share of their workers’ net, set per agency.', { x: 0.5, y: 3.65, w: 4.6, h: 0.9,
    fontSize: 14, color: C.accent6, margin: 0, valign: 'top', isTextBox: true });

  pres.addSection({ title: 'Market and model' });
  s = L.content(pres, 'Market and model', 'A huge market, barely online', 'India home services, by Redseer',
    'Online booking is under one percent of the market today and growing at around 20% a year. Chart values are the midpoints of Redseer’s ranges.');
  L.stat(s, C, { x: 0.5, y: 1.5, w: 3.6, value: '₹5.1 lakh cr', label: 'Home-services market in India, FY25' });
  L.stat(s, C, { x: 0.5, y: 2.75, w: 3.6, value: '<1%', label: 'of it booked online today', color: C.accent2 });
  L.stat(s, C, { x: 0.5, y: 4.0, w: 3.6, value: '18–22%', label: 'yearly growth of online, to FY30', color: C.accent3, valueSize: 32 });
  s.addChart(pres.charts.BAR, [{ name: 'Online market (₹ crore)', labels: ['FY25', 'FY30 (projected)'], values: [4200, 8650] }], {
    x: 4.5, y: 1.5, w: 5.0, h: 3.3, barDir: 'col', chartColors: ['2251D1'], showValue: true,
    dataLabelPosition: 'outEnd', dataLabelFormatCode: '₹#,##0" cr"', dataLabelFontSize: 12, dataLabelColor: '1D2939',
    showTitle: true, title: 'Online home services (₹ crore)', titleFontSize: 13, titleColor: '1D2939',
    titleFontFace: '+mn-lt', dataLabelFontFace: '+mn-lt', catAxisLabelFontFace: '+mn-lt', valAxisLabelFontFace: '+mn-lt',
    catAxisLabelColor: '5F6B7E', valAxisHidden: true, valGridLine: { style: 'none' }, catGridLine: { style: 'none' },
    showLegend: false, catAxisLabelFontSize: 12, valAxisMaxVal: 10000,
  });
  s.addText(MARKET_SRC, { x: 4.5, y: 4.85, w: 5.0, h: 0.3, fontSize: 10, color: '98A2B3', margin: 0, isTextBox: true });

  s = L.content(pres, 'Market and model', 'A fee on every paid job, shown upfront',
    'The customer sees the platform fee as its own line before booking',
    'Starter pricing: 10% platform fee per service, set in Pricing Configuration. Emergency, night and weekend jobs earn more because the fee is a percentage.');
  priceTable(s, C, { x: 0.5, y: 1.6, w: 5.0, colW: [2.3, 1.2, 1.5], rowH: 0.42, fontSize: 14 });
  await L.iconGrid(pres, s, C, [
    { Icon: fa.FaBolt, title: 'Emergency', body: '2× base price', color: C.accent2 },
    { Icon: fa.FaMoon, title: 'Night and weekend', body: '+₹100 / +₹75' },
    { Icon: fa.FaBan, title: 'Late cancellation', body: 'Fee once the pro is on the way', color: C.accent6 },
  ], { x: 5.9, w: 3.6, cols: 1, y: 1.6, rowH: 1.1 });

  s = L.content(pres, 'Market and model', 'About ₹31 per job after costs', 'Illustrative unit economics at starter pricing',
    'Assumptions: ₹555 average service charges, 10% fee, about 2% payment cost on the amount charged, ₹50 coupon on one job in five. Not results.');
  L.table(s, C, [
    ['Per job', { text: '₹', options: { align: 'right' } }],
    ['Platform fee (10% of ₹555)', { text: '55.50', options: { align: 'right' } }],
    ['Payment gateway (≈2% of ₹720)', { text: '−14.40', options: { align: 'right' } }],
    ['Coupons (₹50 × 20%)', { text: '−10.00', options: { align: 'right' } }],
    [{ text: 'Contribution', options: { bold: true } }, { text: '≈31', options: { align: 'right', bold: true } }],
  ], { x: 0.5, y: 1.6, w: 4.0, colW: [3.0, 1.0], rowH: 0.42, fontSize: 14 });
  s.addChart(pres.charts.BAR, [
    { name: 'Platform fee', labels: ['100 / day', '500 / day', '2,000 / day'], values: [1.67, 8.33, 33.3] },
    { name: 'Contribution', labels: ['100 / day', '500 / day', '2,000 / day'], values: [0.93, 4.67, 18.7] },
  ], {
    x: 4.9, y: 1.5, w: 4.6, h: 3.5, barDir: 'col', chartColors: ['2251D1', 'F2A516'], showValue: true,
    dataLabelPosition: 'outEnd', dataLabelFontSize: 11, dataLabelColor: '1D2939', dataLabelFormatCode: '0.0#',
    showTitle: true, title: 'Per month, ₹ lakh', titleFontSize: 13, titleColor: '1D2939',
    titleFontFace: '+mn-lt', dataLabelFontFace: '+mn-lt', catAxisLabelFontFace: '+mn-lt', valAxisLabelFontFace: '+mn-lt', legendFontFace: '+mn-lt',
    catAxisLabelColor: '5F6B7E', valAxisHidden: true, valGridLine: { style: 'none' }, catGridLine: { style: 'none' },
    showLegend: true, legendPos: 'b', legendFontSize: 12, catAxisLabelFontSize: 12,
  });

  pres.addSection({ title: 'Plan' });
  s = L.content(pres, 'Plan', 'Go to market one city at a time', 'Supply first, then demand, with reviews from day one',
    'The reviews engine is the cheapest acquisition channel we have: every good job produces a public page the worker shares.');
  L.flow(pres, s, C, [
    { title: 'Supply', body: 'Weeks 1–4: verify 50–100 workers through agencies and trade schools.' },
    { title: 'Demand', body: 'Weeks 3–8: launch coupons and housing-society partnerships.' },
    { title: 'Reviews', body: 'Day 1 on: rate every job; workers share their profile pages.' },
    { title: 'Retain', body: 'Month 3 on: seasonal reminders, maintenance plans, next city.' },
  ], { color: C.accent3 });
  L.callout(pres, s, C, 'Targets: 50–100 verified workers before demand campaigns; 40% of paid jobs reviewed.', { y: 4.35, h: 0.6 });

  s = L.content(pres, 'Plan', 'Built, tested and live today', 'jihajoor.com, partner.jihajoor.com and admin.jihajoor.com',
    'Everything on this slide is in production. The booking flow test runs on every change and covers sign-in to review.');
  L.stat(s, C, { x: 0.5, y: 1.5, w: 2.1, value: '3', label: 'apps on web, Android and iOS' });
  L.stat(s, C, { x: 2.8, y: 1.5, w: 2.1, value: '20', label: 'backend services in 5 deployables', color: C.accent3 });
  L.stat(s, C, { x: 5.1, y: 1.5, w: 2.1, value: '10', label: 'automated CI workflows', color: C.accent2 });
  L.stat(s, C, { x: 7.4, y: 1.5, w: 2.1, value: '0', label: 'known npm vulnerabilities', color: C.accent4 });
  await L.iconGrid(pres, s, C, [
    { Icon: fa.FaLock, title: 'Secure by design', body: 'HttpOnly sessions, BCrypt passwords, WAF, rate limits.' },
    { Icon: fa.FaCreditCard, title: 'Real payments', body: 'Razorpay with signature checks; credited once.', color: C.accent3 },
  ], { y: 3.35, rowH: 1.4 });

  s = L.content(pres, 'Plan', 'What comes next', 'Revenue and reach on top of what is built',
    'Each item reuses existing parts: public profiles, ratings, the agency queue and per-service pricing.');
  await L.cardsRow(pres, s, C, [
    { Icon: fa.FaStar, title: 'Featured pros', body: 'Top-rated workers pay to appear first.', titleSize: 16 },
    { Icon: fa.FaBuilding, title: 'Agency plans', body: 'Queue priority and review insights.', color: C.accent3, titleSize: 16 },
    { Icon: fa.FaCalendarCheck, title: 'Maintenance plans', body: 'Yearly AC and appliance care, paid upfront.', color: C.accent2, titleSize: 16 },
    { Icon: fa.FaBriefcase, title: 'Business accounts', body: 'Societies and offices on monthly invoices.', color: C.text2, titleSize: 16 },
  ], { y: 1.6, h: 2.6, gap: 0.25 });
  L.callout(pres, s, C, 'Also next: device push notifications, more categories, and the second city.', { fill: 'EEF2FD', color: '0F2A6E' });

  pres.addSection({ title: 'Close' });
  closing(pres, 'Close', 'Join us in building India’s most trusted home-services brand', [
    'Customers: book at jihajoor.com',
    'Workers and agencies: join at partner.jihajoor.com and admin.jihajoor.com',
    'Partners and investors: Condigence LLP, condigence.com',
  ]);
  await L.save(pres, path.join(OUT, 'JiHajoor_Pitch_Deck.pptx'));
}

// =====================================================================================
async function revenue() {
  const { pres, C } = L.newDeck('Ji-Hajoor — Revenue Model');
  pres.addSection({ title: 'Opening' });
  await titleSlide(pres, C, 'Opening', 'Revenue Model', 'How Ji-Hajoor earns on every job',
    'Updated 30 October 2026 · Live figures and illustrative plans', fa.FaRupeeSign);

  pres.addSection({ title: 'How it works' });
  let s = L.content(pres, 'How it works', 'How money moves on every job', 'One payment from the customer, split four ways',
    'The customer pays once through Razorpay. The worker’s share goes to their wallet; Ji-Hajoor keeps the platform fee; GST is paid to the government; an agency share applies only to agency workers.');
  L.box(pres, s, C, { x: 0.5, y: 2.65, w: 2.0, h: 1.0, text: 'Customer pays ₹388.10', fill: C.text2, color: C.background1 });
  L.arrow(pres, s, { x: 2.55, y: 3.15, w: 0.6, h: 0 });
  L.box(pres, s, C, { x: 3.2, y: 2.65, w: 1.9, h: 1.0, text: 'Razorpay', fill: C.accent1, color: C.background1 });
  L.arrow(pres, s, { x: 5.15, y: 2.0, w: 0.75, h: 1.15, flipV: true });
  L.arrow(pres, s, { x: 5.15, y: 3.15, w: 0.75, h: 0 });
  L.arrow(pres, s, { x: 5.15, y: 3.15, w: 0.75, h: 1.15 });
  await L.card(pres, s, C, { x: 5.95, y: 1.55, w: 3.55, h: 0.95, title: 'Worker · ₹299.00', body: 'Service charges, to the wallet', titleSize: 16, bodySize: 14 });
  await L.card(pres, s, C, { x: 5.95, y: 2.7, w: 3.55, h: 0.95, title: 'Ji-Hajoor · ₹29.90', body: 'Platform fee (10%)', titleSize: 16, bodySize: 14, fill: 'FFF6E2' });
  await L.card(pres, s, C, { x: 5.95, y: 3.85, w: 3.55, h: 0.95, title: 'Government · ₹59.20', body: 'GST at 18%', titleSize: 16, bodySize: 14 });

  s = L.content(pres, 'How it works', 'Every price is itemised before booking', 'Starter values, set per service in Pricing Configuration',
    'Staff can change any of these per service without a release.');
  L.table(s, C, [
    ['Part', 'Starter value', 'Who keeps it'],
    ['Base price', '₹299 to ₹1,499 per service', 'Worker'],
    ['Travel', '₹8 per km, capped at ₹150', 'Worker'],
    ['Night / weekend surcharge', '₹100 / ₹75', 'Worker'],
    ['Emergency', '2× the base price', 'Worker'],
    [{ text: 'Platform fee', options: { bold: true } }, { text: '10% of service charges', options: { bold: true } }, { text: 'Ji-Hajoor', options: { bold: true } }],
    ['GST', '18%', 'Government'],
    ['Coupon', 'Flat or % off, capped', 'Paid by Ji-Hajoor'],
  ], { y: 1.6, colW: [3.0, 3.6, 2.4], rowH: 0.4, fontSize: 14 });

  s = L.content(pres, 'How it works', 'Agencies earn from their workers’ jobs', 'A ₹1,000 job with a 10% fee and a 10% agency share',
    'The agency share is taken from the worker’s net, never from the customer. It is set per agency between 0 and 50%.');
  s.addChart(pres.charts.BAR, [
    { name: 'Ji-Hajoor', labels: ['Agency worker', 'Independent worker'], values: [100, 100] },
    { name: 'Agency', labels: ['Agency worker', 'Independent worker'], values: [90, 0] },
    { name: 'Worker', labels: ['Agency worker', 'Independent worker'], values: [810, 900] },
  ], {
    x: 0.5, y: 1.55, w: 9.0, h: 2.7, barDir: 'bar', barGrouping: 'stacked', chartColors: ['F2A516', '0F766E', '2251D1'],
    showValue: true, dataLabelPosition: 'ctr', dataLabelColor: 'FFFFFF', dataLabelFontSize: 13, dataLabelFormatCode: '₹#,##0;;',
    dataLabelFontFace: '+mn-lt', catAxisLabelFontFace: '+mn-lt', valAxisLabelFontFace: '+mn-lt', legendFontFace: '+mn-lt',
    catAxisLabelColor: '1D2939', catAxisLabelFontSize: 13, valAxisHidden: true, valGridLine: { style: 'none' },
    catGridLine: { style: 'none' }, showLegend: true, legendPos: 'b', legendFontSize: 12, barGapWidthPct: 60,
  });
  L.callout(pres, s, C, 'Agencies bring teams without Ji-Hajoor paying for recruitment; workers choose to join and can leave.', { y: 4.4, h: 0.6 });

  pres.addSection({ title: 'Streams and economics' });
  s = L.content(pres, 'Streams and economics', 'Revenue streams: live today and next', 'The next four reuse parts that are already built',
    'Live today: platform fee, higher-value jobs and cancellation fees. Next: proposals for the coming quarters.');
  await L.card(pres, s, C, { x: 0.5, y: 1.6, w: 4.35, h: 3.35, Icon: fa.FaCheckCircle, color: C.accent4, kicker: 'LIVE TODAY',
    title: 'Earning now', body: [
      { text: 'Platform fee on every paid job', options: { bullet: true, breakLine: true } },
      { text: 'Emergency (2× base), night and weekend jobs', options: { bullet: true, breakLine: true } },
      { text: 'Cancellation fees once a pro is on the way', options: { bullet: true } }] });
  await L.card(pres, s, C, { x: 5.15, y: 1.6, w: 4.35, h: 3.35, Icon: fa.FaRocket, color: C.accent2, kicker: 'NEXT',
    title: 'Proposed', body: [
      { text: 'Featured professionals', options: { bullet: true, breakLine: true } },
      { text: 'Agency plans', options: { bullet: true, breakLine: true } },
      { text: 'Maintenance plans (AMC)', options: { bullet: true, breakLine: true } },
      { text: 'Parts margin and business accounts', options: { bullet: true } }] });

  s = L.content(pres, 'Streams and economics', 'About ₹31 per job after costs', 'Illustrative, at starter pricing',
    'Assumptions: ₹555 average service charges (mean of the eight starter services), 10% fee, payment cost about 2% of the ₹720 charged, ₹50 coupon on one job in five.');
  L.table(s, C, [
    ['Per job', { text: '₹', options: { align: 'right' } }],
    ['Platform fee (10% of ₹555)', { text: '55.50', options: { align: 'right' } }],
    ['Payment gateway (≈2% of ₹720)', { text: '−14.40', options: { align: 'right' } }],
    ['Coupons (₹50 × 20%)', { text: '−10.00', options: { align: 'right' } }],
    [{ text: 'Contribution per job', options: { bold: true } }, { text: '≈31', options: { align: 'right', bold: true } }],
  ], { x: 0.5, y: 1.6, w: 5.0, colW: [3.8, 1.2], rowH: 0.45, fontSize: 14 });
  L.stat(s, C, { x: 6.0, y: 1.6, w: 3.5, value: '₹55.50', label: 'platform fee on an average job' });
  L.stat(s, C, { x: 6.0, y: 3.1, w: 3.5, value: '≈56%', label: 'of the fee left after payment and coupon costs', color: C.accent2 });

  s = L.content(pres, 'Streams and economics', 'Contribution grows with volume', 'Per month, ₹ lakh (illustrative)',
    'Thirty days a month at the per-job figures on the previous slide.');
  s.addChart(pres.charts.BAR, [
    { name: 'Platform fee', labels: ['100 jobs / day', '500 jobs / day', '2,000 jobs / day'], values: [1.67, 8.33, 33.3] },
    { name: 'Contribution', labels: ['100 jobs / day', '500 jobs / day', '2,000 jobs / day'], values: [0.93, 4.67, 18.7] },
  ], {
    x: 0.5, y: 1.5, w: 9.0, h: 3.6, barDir: 'col', chartColors: ['2251D1', 'F2A516'], showValue: true,
    dataLabelPosition: 'outEnd', dataLabelFontSize: 12, dataLabelColor: '1D2939', dataLabelFormatCode: '0.0#',
    dataLabelFontFace: '+mn-lt', catAxisLabelFontFace: '+mn-lt', valAxisLabelFontFace: '+mn-lt', legendFontFace: '+mn-lt',
    catAxisLabelColor: '5F6B7E', catAxisLabelFontSize: 13, valAxisHidden: true, valGridLine: { style: 'none' },
    catGridLine: { style: 'none' }, showLegend: true, legendPos: 'b', legendFontSize: 12,
  });

  s = L.content(pres, 'Streams and economics', 'Four levers we control', 'All four are settings in Ji-Hajoor Admin',
    'Watch these weekly alongside bookings and ratings.');
  await L.iconGrid(pres, s, C, [
    { Icon: fa.FaPercent, title: 'Fee rate per service', body: 'Higher on specialist jobs, lower where price-sensitive.' },
    { Icon: fa.FaBolt, title: 'Emergency share', body: 'Emergency jobs earn twice the fee in rupees.', color: C.accent2 },
    { Icon: fa.FaSnowflake, title: 'Job value mix', body: 'Deep cleaning and AC jobs lift the average.', color: C.accent3 },
    { Icon: fa.FaTag, title: 'Coupon spend', body: 'Caps, limits and expiry on every coupon.', color: C.accent5 },
  ]);

  s = L.content(pres, 'Streams and economics', 'To settle before taking real money', 'Decisions for the founders',
    'These are configuration and policy decisions, not missing features.');
  await L.iconGrid(pres, s, C, [
    { Icon: fa.FaBalanceScale, title: 'Align the fee', body: 'Payment takes 20% of the total; estimates show 10%. Pick one.', color: C.accent5 },
    { Icon: fa.FaFileInvoice, title: 'GST treatment', body: 'Confirm with your accountant who collects and pays it.', color: C.accent2 },
    { Icon: fa.FaCreditCard, title: 'Go live on Razorpay', body: 'Add live keys and switch the payment simulator off.' },
    { Icon: fa.FaUniversity, title: 'Agency payouts', body: 'The agency share is a ledger today; pay it out monthly.', color: C.accent3 },
  ]);

  pres.addSection({ title: 'Close' });
  closing(pres, 'Close', 'A transparent fee that grows with every trusted job', [
    'Full model: docs/REVENUE_MODEL.md',
    'Market sources: Redseer via Outlook Business (Sept 2025)',
  ]);
  await L.save(pres, path.join(OUT, 'JiHajoor_Revenue_Model.pptx'));
}

// =====================================================================================
async function tech() {
  const { pres, C } = L.newDeck('Ji-Hajoor — Technology and Security');
  pres.addSection({ title: 'Opening' });
  await titleSlide(pres, C, 'Opening', 'Technology & Security', 'How Ji-Hajoor is built and protected',
    'Updated 30 October 2026 · Tech stack, security review and reviews engine', fa.FaServer);

  pres.addSection({ title: 'Platform' });
  let s = L.content(pres, 'Platform', 'At a glance', 'An event-driven marketplace on Railway, ready for AWS',
    'Counts from the repository on 30 October 2026.');
  L.stat(s, C, { x: 0.5, y: 1.55, w: 2.1, value: '3', label: 'apps: web, Android, iOS' });
  L.stat(s, C, { x: 2.8, y: 1.55, w: 2.1, value: '20', label: 'services in 5 deployables', color: C.accent3 });
  L.stat(s, C, { x: 5.1, y: 1.55, w: 2.1, value: '14', label: 'event types, via Kafka', color: C.accent2 });
  L.stat(s, C, { x: 7.4, y: 1.55, w: 2.1, value: '10', label: 'CI workflows', color: C.accent4 });
  await L.iconGrid(pres, s, C, [
    { Icon: fa.FaCloud, title: 'Live on Railway', body: 'Every app and service deploys from one repository on each merge.' },
    { Icon: fa.FaServer, title: 'Ready for AWS', body: 'EKS, RDS and MSK prepared in Terraform and Helm.', color: C.accent2 },
  ], { y: 3.0, rowH: 1.2 });
  L.callout(pres, s, C, 'Every change runs unit tests, lint, security scans and a full booking-to-review test before production.', { y: 4.35, h: 0.6, fill: 'EEF2FD', color: '0F2A6E' });

  s = L.content(pres, 'Platform', 'One gateway in, four deployables behind it', 'Apps → nginx → api-gateway → services → data',
    'Each deployable is one JVM hosting several services, each with its own schema. Services talk over HTTP and Kafka with a transactional outbox.');
  const apps = [['Ji-Hajoor', C.accent1], ['Ji-Hajoor Pro', C.accent3], ['Ji-Hajoor Admin', C.text2]];
  apps.forEach(([t, col], i) => L.box(pres, s, C, { x: 0.5, y: 1.55 + i * 0.75, w: 1.8, h: 0.6, text: t, fill: col, color: C.background1, fontSize: 13 }));
  L.arrow(pres, s, { x: 2.35, y: 2.4, w: 0.45, h: 0 });
  L.box(pres, s, C, { x: 2.85, y: 1.55, w: 1.5, h: 2.1, text: 'api-gateway\nJWT · WAF · rate limits', fill: C.accent2, color: '3B2400', fontSize: 13 });
  L.arrow(pres, s, { x: 4.4, y: 2.4, w: 0.45, h: 0 });
  [['identity-app', 'auth, customers, workers, verification'], ['jobs-app', 'booking, dispatch, pricing, chat'],
   ['money-app', 'payments, invoices'], ['engagement-app', 'notify, ratings, complaints, admin']].forEach(([t, d], i) => {
    L.box(pres, s, C, { x: 4.9, y: 1.5 + i * 0.58, w: 4.6, h: 0.5, text: `${t}  ·  ${d}`, fontSize: 12, bold: false });
  });
  L.box(pres, s, C, { x: 4.9, y: 3.95, w: 1.45, h: 0.5, text: 'PostgreSQL', fill: C.text2, color: C.background1, fontSize: 12 });
  L.box(pres, s, C, { x: 6.47, y: 3.95, w: 1.45, h: 0.5, text: 'Redis', fill: C.text2, color: C.background1, fontSize: 12 });
  L.box(pres, s, C, { x: 8.05, y: 3.95, w: 1.45, h: 0.5, text: 'Kafka', fill: C.text2, color: C.background1, fontSize: 12 });
  s.addText('Partners: Razorpay · MSG91 SMS · Google and Apple sign-in · Email (SES) · S3', { x: 0.5, y: 4.65, w: 9.0, h: 0.35,
    fontSize: 13, color: C.accent6, margin: 0, isTextBox: true });

  s = L.content(pres, 'Platform', 'Tech stack', 'Mainstream, well-supported choices at every layer', 'Chosen for hiring ease and long-term support.');
  L.table(s, C, [
    ['Layer', 'Choice'],
    ['Apps', 'React 19, TypeScript, MUI, Vite; Capacitor for Android and iOS'],
    ['Backend', 'Java 25, Spring Boot 4.1, Spring Cloud Gateway'],
    ['Data', 'PostgreSQL (a schema per service, Flyway), Redis'],
    ['Events', 'Kafka with a transactional outbox'],
    ['Hosting', 'Railway (Docker) today; AWS EKS, RDS, MSK ready in Terraform and Helm'],
    ['CI/CD', 'GitHub Actions; gitleaks, Trivy and npm audit on every change'],
    ['Partners', 'Razorpay, MSG91, Google and Apple sign-in, SES email, S3'],
  ], { y: 1.55, colW: [1.6, 7.4], rowH: 0.42, fontSize: 14 });

  pres.addSection({ title: 'Security' });
  sectionSlide(pres, 'Security', 'SECURITY REVIEW · 30 OCTOBER 2026', 'Secure by design, checked on every change',
    'Defence in depth from the browser to the database. No system can promise it cannot be hacked; this is how we make it hard.');

  s = L.content(pres, 'Security', 'Security in four layers', 'What protects users today', 'Updated after the 30 October review.');
  await L.cardsRow(pres, s, C, [
    { Icon: fa.FaGlobe, title: 'Edge and browser', body: 'HTTPS + HSTS, strict CSP, no framing, WAF, IP rate limits, logs without query strings.', titleSize: 16, bodySize: 14 },
    { Icon: fa.FaKey, title: 'Identity', body: 'Short-lived JWT checked twice, role rules on every endpoint, refresh token in an HttpOnly cookie.', color: C.accent3, titleSize: 16, bodySize: 14 },
    { Icon: fa.FaDatabase, title: 'Data', body: 'BCrypt passwords, strong password policy, schema per service, uploads checked by content.', color: C.accent2, titleSize: 16, bodySize: 14 },
    { Icon: fa.FaShieldAlt, title: 'Money and supply chain', body: 'Razorpay signatures, credited once; secret scanning, dependency and image scans.', color: C.accent4, titleSize: 16, bodySize: 14 },
  ], { y: 1.55, h: 3.45, gap: 0.2 });

  s = L.content(pres, 'Security', 'What the 30 October review fixed', 'Six findings, all fixed and deployed',
    'Each fix has tests, and the end-to-end booking flow passed on main before deployment.');
  L.table(s, C, [
    ['Finding', 'Fix'],
    ['Refresh token readable by page scripts', 'HttpOnly, Secure, SameSite=Strict cookie scoped to /api/auth'],
    ['Weak passwords accepted', '10+ chars, letter and digit; no common, leet, sequence or personal passwords'],
    ['Rate limits keyed on a spoofable header', 'Railway’s X-Real-IP, then the last proxy hop'],
    ['Photo and icon links unchecked', 'Only https:// or same-site paths; no javascript: or data:'],
    ['Codes and tokens in web-server logs', 'Query strings masked in nginx access logs'],
    ['Known npm vulnerabilities', 'Updated: 0 in all three apps'],
  ], { y: 1.55, colW: [3.6, 5.4], rowH: 0.45, fontSize: 13 });

  pres.addSection({ title: 'Reviews engine' });
  s = L.content(pres, 'Reviews engine', 'The reviews engine', 'New on 30 October: more reviews, more trust, more bookings',
    'Payment completes, rating-review-service opens a prompt for the customer, the apps surface it, and one reminder goes out after 24 hours through the outbox and notification-service.');
  L.flow(pres, s, C, [
    { title: 'Paid', body: 'PaymentCompleted opens a review prompt' },
    { title: 'Asked', body: 'Receipt, Rate card and Home banner' },
    { title: 'Reminded', body: 'Once, after 24 h, by push, SMS and in-app' },
    { title: 'Checked', body: 'Fraud checks hold doubtful reviews for staff' },
    { title: 'Public', body: 'Shown on the worker’s page; the worker replies' },
  ], { textH: 1.4 });
  L.callout(pres, s, C, 'Public pages never show who wrote a review, and exist only for verified workers.', { y: 4.4, h: 0.6, fill: 'EEF2FD', color: '0F2A6E' });

  s = L.content(pres, 'Reviews engine', 'New endpoints', 'All behind the gateway; public ones are read-only',
    'Roles are enforced by RbacEnforcementFilter in each service and by the gateway allow-list for public paths.');
  L.table(s, C, [
    ['Endpoint', 'Who', 'Purpose'],
    ['GET /reviews/prompts/mine', 'Customer, worker', 'Jobs waiting for my rating'],
    ['GET /reviews/received', 'Worker', 'Reviews I received'],
    ['POST /reviews/{id}/reply', 'Worker (reviewee)', 'Reply publicly, up to 500 chars'],
    ['GET /public/workers/{id}', 'Anyone', 'Verified worker’s public profile'],
    ['GET /public/workers/{id}/reviews', 'Anyone', 'Rating summary and reviews, paged'],
  ], { y: 1.55, colW: [3.6, 2.0, 3.4], rowH: 0.45, fontSize: 13 });

  pres.addSection({ title: 'Delivery' });
  s = L.content(pres, 'Delivery', 'From commit to production', 'Every push to main deploys all services on Railway',
    'Failing tests are annotated one by one in CI so fixes are quick.');
  L.flow(pres, s, C, [
    { title: 'Test', body: 'Build and unit tests; each failure annotated' },
    { title: 'Check', body: 'Lint, types, gitleaks, Trivy, npm audit' },
    { title: 'Prove', body: 'Booking flow: sign-in to payment to review' },
    { title: 'Deploy', body: 'Merge to main; Railway deploys all services' },
    { title: 'Verify', body: 'Smoke test against the live site' },
  ], { color: C.accent3 });

  s = L.content(pres, 'Delivery', 'Decisions still open', 'Owned by the founders; none changed without approval',
    'All are environment settings or policy decisions.');
  await L.iconGrid(pres, s, C, [
    { Icon: fa.FaCreditCard, title: 'Go live on payments', body: 'Switch the simulator off; add Razorpay live keys.', color: C.accent5 },
    { Icon: fa.FaSms, title: 'Remove the fixed OTP', body: 'Once MSG91 delivery is confirmed.', color: C.accent2 },
    { Icon: fa.FaSyncAlt, title: 'Rotate secrets', body: 'JWT_SECRET and the test staff password.' },
    { Icon: fa.FaBalanceScale, title: 'Align the platform fee', body: 'Payment uses 20% of total; estimates show 10%.', color: C.accent3 },
  ]);

  pres.addSection({ title: 'Close' });
  closing(pres, 'Close', 'Built to be trusted, tested on every change', [
    'Architecture detail: docs/ARCHITECTURE.md and JiHajoor_Architecture.pptx',
    'API reference: docs/API_CONTRACTS.md',
  ]);
  await L.save(pres, path.join(OUT, 'JiHajoor_Tech_Stack.pptx'));
}

// =====================================================================================
async function brand() {
  const { pres, C } = L.newDeck('Ji-Hajoor — Brand and Marketing');
  pres.addSection({ title: 'Opening' });
  await titleSlide(pres, C, 'Opening', 'Brand & Marketing', 'Naming, identity and the launch playbook',
    'Updated 30 October 2026 · For everyone who writes, designs or sells', fa.FaBullhorn);

  pres.addSection({ title: 'Brand' });
  let s = L.content(pres, 'Brand', 'Verified help you can trust, at a fair price', 'Our promise, and the three proofs we repeat everywhere',
    'Every creative should land at least one of these three proofs.');
  await L.cardsRow(pres, s, C, [
    { Icon: fa.FaIdCard, title: 'Verified', body: 'Every professional passes document checks and a background check.' },
    { Icon: fa.FaFileInvoice, title: 'Transparent', body: 'An itemised price before you book. You approve any parts.', color: C.accent2 },
    { Icon: fa.FaComments, title: 'Accountable', body: 'Live tracking, chat, public reviews, and a real person on every complaint.', color: C.accent3 },
  ], { y: 1.6, h: 3.3 });

  s = L.content(pres, 'Brand', 'Write it the same way, every time', 'The brand name and its sign-off',
    'Consistency builds recognition. Use the code spelling only where a hyphen cannot go.');
  L.table(s, C, [
    ['Use', 'Don’t use'],
    ['Ji-Hajoor', 'Ji Hajoor · JiHajoor · Jihajoor · ji-hajoor · JH'],
    ['jihajoor.com (web addresses only)', 'Ji-Hajoor.com'],
    ['JiHajoor (file names and code only)', 'Ji_Hajoor'],
  ], { y: 1.55, colW: [4.0, 5.0], rowH: 0.45, fontSize: 14 });
  L.callout(pres, s, C, 'Sign-off on every page, deck and document:  © 2026 Ji-Hajoor. Verified Help. Anytime. Anywhere. Powered by Condigence LLP.', { y: 3.6, h: 0.9, fill: 'EEF2FD', color: '0F2A6E' });

  s = L.content(pres, 'Brand', 'One family of apps', 'Each app has one name, one address and one line',
    'Internally it is fine to say customer app, partner app and admin portal.');
  await L.cardsRow(pres, s, C, [
    { Icon: fa.FaHome, kicker: 'JIHAJOOR.COM · CUSTOMERS', title: 'Ji-Hajoor', body: 'Verified Help. Anytime. Anywhere.\n\nBlue #2251D1' },
    { Icon: fa.FaTools, kicker: 'PARTNER.JIHAJOOR.COM · WORKERS', title: 'Ji-Hajoor Pro', body: 'Earn More. Serve Better.\n\nTeal #0F766E', color: C.accent3 },
    { Icon: fa.FaChartLine, kicker: 'ADMIN.JIHAJOOR.COM · STAFF', title: 'Ji-Hajoor Admin', body: 'Agencies see My agency.\n\nDeep blue #0F2A6E', color: C.text2 },
  ], { y: 1.6, h: 3.3 });

  s = L.content(pres, 'Brand', 'People and things: one word each', 'What we say, what customers see, and what the code says',
    'Never show code names such as Tenant or Provider to users.');
  L.table(s, C, [
    ['We say', 'Customers see', 'In code'],
    ['Customer', 'you', 'CUSTOMER'],
    ['Worker', 'professional (pro)', 'Provider · SERVICE_PROVIDER'],
    ['Agency', 'agency', 'Tenant · TENANT_ADMIN'],
    ['Ji-Hajoor staff', 'our team', 'SUPER_ADMIN, ADMIN, FINANCE_ADMIN, …'],
    ['Platform fee', 'platform fee', 'platformFee'],
    ['Public profile', 'profile and reviews', '/public/workers/{id}'],
    ['Verified', 'Verified by Ji-Hajoor', 'APPROVED'],
  ], { y: 1.55, colW: [2.4, 2.8, 3.8], rowH: 0.4, fontSize: 13 });

  s = L.content(pres, 'Brand', 'Colour and type', 'Blue leads; marigold highlights one thing per creative',
    'Use blue for customer material and teal for worker recruitment. Headings in Cambria, body in Calibri; the apps use Plus Jakarta Sans.');
  const sw = [['Ji-Hajoor Blue', '2251D1', 'FFFFFF'], ['Deep Blue', '0F2A6E', 'FFFFFF'], ['Marigold', 'F2A516', '3B2400'],
    ['Pro Teal', '0F766E', 'FFFFFF'], ['Success', '12875A', 'FFFFFF'], ['Canvas', 'F7F8FA', '1D2939']];
  sw.forEach(([n, h, t], i) => {
    const x = 0.5 + i * 1.53;
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y: 1.6, w: 1.38, h: 1.6, rectRadius: 0.08, fill: { color: h },
      line: { color: h === 'F7F8FA' ? 'D0D5DD' : h }, objectName: `swatch-${i}` });
    s.addText([{ text: n, options: { bold: true, breakLine: true } }, { text: `#${h}` }], { x: x + 0.1, y: 2.35, w: 1.2, h: 0.75,
      fontSize: 12, color: t, margin: 0, valign: 'bottom', isTextBox: true });
  });
  s.addText([{ text: 'Cambria for headings', options: { fontFace: 'Cambria', fontSize: 24, bold: true, color: '0F2A6E', breakLine: true } },
    { text: 'Calibri for body text in decks and print, 14 pt and up.', options: { fontFace: 'Calibri', fontSize: 16, color: '1D2939' } }],
  { x: 0.5, y: 3.5, w: 9.0, h: 1.3, margin: 0, valign: 'top', isTextBox: true });

  s = L.content(pres, 'Brand', 'Sound like a helpful neighbour', 'Warm, plain and direct; say what happens next',
    'Avoid jargon, exclamation marks and promises we cannot keep, such as 100% safe or never late.');
  L.table(s, C, [
    ['Instead of', 'Write'],
    ['Leverage our best-in-class service ecosystem', 'Book a verified plumber in two minutes.'],
    ['Your request is being processed', 'Finding you a professional.'],
    ['Unacceptable rating detected', 'We’re checking this review before it appears.'],
    ['100% safe, guaranteed', 'Every pro passes ID and background checks.'],
  ], { y: 1.55, colW: [4.5, 4.5], rowH: 0.55, fontSize: 14 });

  pres.addSection({ title: 'Marketing' });
  sectionSlide(pres, 'Marketing', 'MARKETING PLAN', 'Win one city, then the next',
    'Supply first, seeded demand, and a reviews engine that turns every good job into the next booking.');

  s = L.content(pres, 'Marketing', 'Four audiences, four messages', 'Who we talk to and what we tell them',
    'Households and societies are demand; workers and agencies are supply.');
  await L.cardsRow(pres, s, C, [
    { Icon: fa.FaUsers, title: 'Households', body: '“Verified help in minutes. See the price first.”', titleSize: 16 },
    { Icon: fa.FaCity, title: 'Societies', body: '“One app for every flat’s repairs, with reviews you can check.”', color: C.text2, titleSize: 16 },
    { Icon: fa.FaHardHat, title: 'Workers', body: '“Earn More. Serve Better. Your reviews bring the next job.”', color: C.accent3, titleSize: 16 },
    { Icon: fa.FaBuilding, title: 'Agencies', body: '“We send the requests; you keep your share.”', color: C.accent2, titleSize: 16 },
  ], { y: 1.6, h: 3.3, gap: 0.25 });

  s = L.content(pres, 'Marketing', 'Launch playbook', 'One city at a time',
    'Targets: 50–100 verified workers before demand campaigns; 40% of paid jobs reviewed.');
  L.flow(pres, s, C, [
    { title: 'Supply', body: 'Weeks 1–4: recruit and verify through agencies, trade schools, WhatsApp groups.' },
    { title: 'Seed demand', body: 'Weeks 3–8: FIRST100 and SOCIETY15 coupons; 10–20 society partners.' },
    { title: 'Reviews', body: 'Day 1 on: every job rated; workers share their public pages.' },
    { title: 'Retain', body: 'Month 3 on: seasonal reminders and maintenance plans.' },
  ], { color: C.accent3 });
  L.callout(pres, s, C, 'Measure weekly: bookings, repeat customers, average rating and the share of paid jobs reviewed.', { y: 4.35, h: 0.6 });

  s = L.content(pres, 'Marketing', 'The reviews flywheel', 'Every good job markets the next one, for free',
    'Built into the product: the prompt and reminder, public pages, worker replies and the share card.');
  const loop = [['Great job', 4.25, 1.45], ['Rated', 6.55, 2.35], ['Public page', 6.0, 3.95], ['Worker shares', 2.5, 3.95], ['New customer books', 1.95, 2.35]];
  const cols = [C.accent1, C.accent2, C.accent3, C.accent4, C.text2];
  loop.forEach(([t, x, y], i) => L.box(pres, s, C, { x, y, w: 1.5, h: 0.7, text: t, fill: cols[i], color: i === 1 ? '3B2400' : C.background1, fontSize: 13 }));
  L.arrow(pres, s, { x: 5.8, y: 1.85, w: 0.75, h: 0.5 });
  L.arrow(pres, s, { x: 7.1, y: 3.1, w: 0.2, h: 0.8, flipH: true });
  L.arrow(pres, s, { x: 4.05, y: 4.3, w: 1.9, h: 0, flipH: true });
  L.arrow(pres, s, { x: 2.5, y: 3.1, w: 0.25, h: 0.8, flipH: true, flipV: true });
  L.arrow(pres, s, { x: 3.5, y: 1.85, w: 0.7, h: 0.5, flipV: true });
  s.addText('Target: 40% of paid jobs reviewed', { x: 3.6, y: 2.65, w: 2.8, h: 0.6, fontSize: 14, bold: true, color: C.accent1,
    align: 'center', valign: 'middle', margin: 0, isTextBox: true });

  s = L.content(pres, 'Marketing', 'Channels and how we measure them', 'Start with the free and dense channels',
    'Review cost per booking monthly and move spend to what works.');
  L.table(s, C, [
    ['Channel', 'Use', 'Measure'],
    ['Worker profile sharing', 'Trusted word of mouth, free', 'Profile opens, bookings'],
    ['Housing societies, RWAs', 'Dense demand, low cost', 'Bookings per society'],
    ['Google Search and Maps', '“Plumber near me” intent', 'Cost per booking'],
    ['Instagram, YouTube Shorts', 'Before/after videos, pro stories', 'Views, sign-ups'],
    ['Referral coupons', 'Customers invite neighbours', 'Redemptions'],
  ], { y: 1.55, colW: [3.0, 3.3, 2.7], rowH: 0.48, fontSize: 14 });

  s = L.content(pres, 'Marketing', 'What we measure every week', 'Nine numbers on one page',
    'Report Generation in Ji-Hajoor Admin exports bookings and revenue; review share comes from Review Moderation.');
  const metrics = ['Bookings', 'Repeat customers', 'Cancellation rate', 'Average rating', 'Jobs reviewed', 'Time to accept',
    'Complaints / 100 jobs', 'Active verified workers', 'Coupon cost / new customer'];
  metrics.forEach((m, i) => L.box(pres, s, C, { x: 0.5 + (i % 3) * 3.07, y: 1.6 + Math.floor(i / 3) * 1.1, w: 2.85, h: 0.9,
    text: m, fontSize: 15 }));

  s = L.content(pres, 'Marketing', 'Rules for promotions', 'Coupons are created only in Ji-Hajoor Admin',
    'Every coupon has an expiry date, a per-customer limit and a total limit.');
  await L.card(pres, s, C, { x: 0.5, y: 1.6, w: 4.35, h: 1.95, Icon: fa.FaTag, title: 'FIRST100', body: '₹100 off the first booking; min ₹299; once per customer.', titleSize: 16, bodySize: 14 });
  await L.card(pres, s, C, { x: 5.15, y: 1.6, w: 4.35, h: 1.95, Icon: fa.FaCity, title: 'SOCIETY15', body: '15% off, capped at ₹150, for partner societies.', color: C.text2, titleSize: 16, bodySize: 14 });
  await L.iconGrid(pres, s, C, [
    { Icon: fa.FaRupeeSign, title: 'The estimate is the price', body: 'Never promise a price outside the app.', color: C.accent2 },
    { Icon: fa.FaStar, title: 'Real reviews only', body: 'No invented quotes; get consent for photos.', color: C.accent4 },
  ], { y: 3.85, rowH: 1.2 });

  pres.addSection({ title: 'Close' });
  closing(pres, 'Close', 'One name, one promise, every time', [
    'Full guide: docs/BRAND_AND_MARKETING.md',
    'User guides: docs/guides/',
  ]);
  await L.save(pres, path.join(OUT, 'JiHajoor_Brand_and_Marketing.pptx'));
}

(async () => {
  const only = process.argv[2];
  const all = { pitch, revenue, tech, brand };
  for (const [k, fn] of Object.entries(all)) if (!only || only === k) await fn();
})().catch((e) => { console.error(e); process.exit(1); });
