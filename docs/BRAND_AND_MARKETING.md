# Ji-Hajoor — Brand, Naming and Marketing

*Last updated 30 October 2026. Use this page whenever you write copy, design a creative, or name
something new.*

## 1. Naming conventions

### The brand

| Use | Don't use |
|---|---|
| **Ji-Hajoor** (capital J, hyphen, capital H) | Ji Hajoor, JiHajoor, Jihajoor, ji-hajoor, JH |
| jihajoor.com (in web addresses only) | |
| `JiHajoor` (in file names and code only, where a hyphen can't go) | |

Ji-Hajoor is **Powered by Condigence LLP**. Every page, document and deck ends with:

> © 2026 Ji-Hajoor. Verified Help. Anytime. Anywhere. Powered by Condigence LLP.

### The apps

| App | Who it is for | Name in stores and on screen | Address | Tagline |
|---|---|---|---|---|
| Customer app | Customers | **Ji-Hajoor** | jihajoor.com | *Verified Help. Anytime. Anywhere.* |
| Partner app | Workers (and agencies' workers) | **Ji-Hajoor Pro** | partner.jihajoor.com | *Earn More. Serve Better.* |
| Admin portal | Ji-Hajoor staff and agency administrators | **Ji-Hajoor Admin** (agencies see **My agency**) | admin.jihajoor.com | — |

In internal docs it is fine to say "customer app", "partner app" and "admin portal".

### People and things

| Say (to users and in docs) | Customer-facing wording | In code and APIs |
|---|---|---|
| **Customer** | you | `CUSTOMER`, `/customers/**` |
| **Worker** | **professional** (short: *pro*) | `Provider`, `SERVICE_PROVIDER`, `/providers/**` |
| **Agency** (an *agent provider* in sales talk) | agency | `Tenant`, `TENANT_ADMIN`, `/tenant/**`, `/admin/tenants` |
| **Ji-Hajoor staff** | our team | `SUPER_ADMIN`, `ADMIN`, `FINANCE_ADMIN`, `DISPATCHER`, `SUPPORT_AGENT` |
| **Booking** / job | booking | `Booking` |
| **Platform fee** | platform fee | `platformFee` |
| **Agency share** | — | `sharePercent` on the tenant, `agencyShare` on earnings |
| **Review** / rating | rating, review | `Review`, `ReviewPrompt` |
| **Public profile** | profile and reviews | `/public/workers/{id}` |
| **Verified** (documents + background check passed) | Verified by Ji-Hajoor | `APPROVED` |

Rules:

- Never show code names (`Tenant`, `Provider`) to users.
- Customers book a **professional**; workers see themselves as **workers** or **pros**; staff
  and agencies say **worker**.
- Service names use Title Case: *Tap / Faucet Repair*, *Full Home Deep Cleaning*, *AC Service*.
- Money is written ₹1,499 (no decimals unless needed), with Indian grouping (₹1,50,000 or
  ₹1.5 lakh).
- File names in `docs/`: `UPPER_SNAKE.md` for documents, `JiHajoor_Title_Case.pptx` for decks, and
  the same name with `.pdf` in `docs/pdf/`.

## 2. Brand identity

### Promise

**Verified help you can trust, at a fair price you see before you book.**

Three proofs we repeat everywhere:

1. **Verified:** every professional passes document checks and a background check.
2. **Transparent:** an itemised price before you book; parts approved by you before you pay.
3. **Accountable:** live tracking, in-app chat, public reviews, and a real person on every
   complaint.

### Voice

Warm, plain and direct, like a helpful neighbour. Short sentences. Say what happens next. Avoid
jargon, exclamation marks and promises we can't keep ("100% safe", "never late").

| Instead of | Write |
|---|---|
| "Leverage our best-in-class service ecosystem" | "Book a verified plumber in two minutes." |
| "Your request is being processed" | "Finding you a professional." |
| "Unacceptable rating detected" | "We're checking this review before it appears." |

### Colours

| Role | Customer app (Ji-Hajoor) | Partner app (Ji-Hajoor Pro) |
|---|---|---|
| Primary | Ji-Hajoor Blue `#2251D1` | Pro Teal `#0F766E` |
| Deep | `#0F2A6E` | — |
| Accent | Marigold `#F2A516` | Marigold `#F2A516` |
| Success | `#12875A` | `#12875A` |
| Text | Ink `#0F0F0F`, Body `#344054` | same |
| Background | Canvas `#F7F8FA`, Paper `#FFFFFF` | same |

Use blue for customer-facing material, teal for worker recruitment, and marigold sparingly for one
highlight per creative (a price, a badge, a call to action).

### Typography

The apps use **Plus Jakarta Sans**, falling back to the system font. Decks and print use **Calibri** for
body text and **Cambria** for headings, which every Office install has.

## 3. Marketing plan

### Who we are talking to

| Audience | What they want | Message |
|---|---|---|
| Urban households (25–45, working, renting or owning) | Someone reliable, today, at a fair price | *Verified help in minutes. See the price first.* |
| Housing societies and RWAs | One trusted vendor for many homes | *One app for every flat's repairs, with reviews you can check.* |
| Independent workers | More jobs, fair pay, respect | *Earn More. Serve Better. Your reviews bring the next job.* |
| Agencies | Fill idle team time without more sales staff | *Ji-Hajoor sends the requests; you keep your share.* |

### Launch playbook (one city at a time)

1. **Supply first (weeks 1–4).** Recruit and verify 50–100 workers across the four starter
   categories through agencies, trade schools and WhatsApp groups. Offer a small joining bonus
   after a worker's first five rated jobs, paid from the marketing budget.
2. **Seed demand (weeks 3–8).** Launch coupons in Coupon Management: `FIRST100` (₹100 off the first
   booking, minimum ₹299, one per customer) and `SOCIETY15` (15% off, capped at ₹150, for partner
   societies). Partner with 10–20 housing societies for notice-board QR codes and WhatsApp groups.
3. **Reviews engine (from day 1).** Every paid job asks for a rating; a reminder the next day;
   workers share their public profile pages. Target: 40% of paid jobs reviewed.
4. **Retention.** Monthly "home check" reminders (AC before summer, plumbing before monsoon), and
   maintenance plans once demand is steady.

### Channels

| Channel | Use | Measure |
|---|---|---|
| Worker profile sharing (WhatsApp, visiting cards) | Free, trusted word of mouth | Profile opens, bookings from profile |
| Housing societies and RWAs | Dense demand at low cost | Bookings per society |
| Google Search and Maps | "plumber near me" intent | Cost per booking |
| Instagram and YouTube Shorts | Before/after job videos, worker stories | Views, sign-ups |
| Referral coupons | Customers invite neighbours | Coupon redemptions |
| Local press and radio | City launch | Downloads in launch week |

### Content ideas

- **"Meet your pro"** — 30-second videos of verified workers with their rating.
- **"Price before you book"** — screen recording of the itemised estimate.
- **Before/after** — job photos (with customer consent).
- **Season guides** — "5 checks before summer" (AC), "Monsoon-proof your home" (plumbing, electrical).

### What to measure every week

Bookings, repeat-customer rate, cancellation rate, average rating, share of paid jobs reviewed,
time to accept, complaints per 100 jobs, verified workers active, and coupon cost per new customer.

### Rules for promotions

- Coupons are created only in Ji-Hajoor Admin, with an expiry date, a per-customer limit and a total
  limit.
- Never promise a fixed price outside the app; the estimate is the price.
- Use real reviews only, never invented quotes or ratings. Ask workers and customers for consent
  before using their photos or names.
