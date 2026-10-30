# Ji-Hajoor — Revenue Model

*Last updated 30 October 2026. Figures marked **live** are what the platform does today; figures
marked **illustrative** are planning assumptions, not results.*

## In one line

Ji-Hajoor earns a **platform fee on every paid job**. Customers see the fee as its own line before
they book; workers see it on every job in their earnings; agencies earn their own share from their
workers' jobs.

## Who pays what

| Party | Pays | Receives |
|---|---|---|
| Customer | The itemised price: service charges + platform fee + GST, less any coupon | A verified professional, live tracking, an invoice, the right to complain and be refunded |
| Worker | Nothing to join | The service earnings, credited to their wallet, paid out to their bank on request |
| Agency | Nothing to join | Its agency share (0–50%) of each job its workers do |
| Ji-Hajoor | Payment gateway costs, coupons, marketing | The platform fee |

## How a price is built (live)

Every service has its own pricing, set by Ji-Hajoor staff in **Pricing Configuration**:

| Part | Starter value | Notes |
|---|---|---|
| Base price | ₹299 to ₹1,499 per service | Tap repair ₹299, deep cleaning ₹1,499 |
| Travel | ₹8 per km, capped at ₹150 | |
| Night surcharge | ₹100 | |
| Weekend surcharge | ₹75 | |
| Emergency | 2× the base price | Only on services that offer emergency |
| Busy-time (surge) | 1× (off) | Can be raised per service |
| **Platform fee** | **10% of the service charges** | Ji-Hajoor's revenue |
| GST | 18% | Collected and paid to the government; not revenue |
| Coupon | Flat or % off, with caps and limits | A marketing cost |

**Worked example (live starter pricing):** a scheduled tap repair, no travel or surcharges.

```
Service charges            ₹299.00
Platform fee (10%)         ₹ 29.90   → Ji-Hajoor
GST 18% on ₹328.90         ₹ 59.20   → government
Customer pays              ₹388.10
```

The same job as an **emergency** has ₹598 in service charges and earns ₹59.80 in platform fee.
Emergency, night and weekend jobs earn Ji-Hajoor more because the fee is a percentage.

## The agency split (live)

For a job done by an agency's worker:

```
worker's net (after the platform fee)
  − agency share (0–50%, set per agency)   → the agency
  = credited to the worker's wallet
```

A ₹1,000 job with a 10% fee and a 10% agency share credits ₹100 to Ji-Hajoor, ₹90 to the agency and
₹810 to the worker. An independent worker keeps the whole net.

## Revenue streams

### Live today

1. **Platform fee** on every paid job (10% starter rate, set per service).
2. **Higher-value jobs:** emergency (2× base), night and weekend jobs raise the fee in rupees.
3. **Cancellation fees** after a professional is on the way (set per service; ₹0 by default).

### Next, using what is already built (proposals)

4. **Featured professionals:** top-rated workers pay to appear first on category pages. The public
   profile and rating pages now make this possible.
5. **Agency plans:** a monthly plan for agencies with more queue priority and a dashboard of their
   workers' reviews.
6. **Maintenance plans (AMC):** yearly AC, water purifier or appliance care, sold as a bundle of
   bookings with a discount, paid up front.
7. **Parts margin:** standard parts supplied through Ji-Hajoor at a small margin, already captured
   as parts on each job.
8. **Business accounts:** housing societies, offices and landlords booking many jobs with a monthly
   invoice.

## Unit economics (illustrative)

Assumptions: average job ₹555 in service charges (the average of the eight starter services), 10%
fee, payment gateway cost about 2% of the amount charged, coupons on 20% of jobs at ₹50 each.

| Per job | ₹ |
|---|---|
| Platform fee (10% of ₹555) | 55.50 |
| Payment gateway (≈2% of ₹720 charged) | −14.40 |
| Coupons (₹50 × 20%) | −10.00 |
| **Contribution per job** | **≈31** |

| Jobs per day | Platform fee per month | Contribution per month |
|---|---|---|
| 100 | ₹1.67 lakh | ₹0.93 lakh |
| 500 | ₹8.33 lakh | ₹4.67 lakh |
| 2,000 | ₹33.3 lakh | ₹18.7 lakh |

Levers that move contribution most: the fee rate per service, the share of emergency jobs, the
average job value (deep cleaning and AC jobs), and coupon spend.

## Market (external)

- India's home-services market was about **₹5.1–5.2 lakh crore** in FY25, and **under 1%** of it is
  booked online. ([Redseer, via Outlook Business, Sept 2025](https://www.outlookbusiness.com/news/indias-online-home-services-market-to-grow-at-22-to-hit-88-billion-by-fy30-report))
- The online segment was **₹4,100–4,300 crore** in FY25 and is expected to grow **18–22% a year**
  to **₹8,500–8,800 crore** by FY30 (same source).
- The leading online player, Urban Company, reported its first annual profit in FY25 (₹28.5 crore),
  which shows the model can be profitable at scale.
  ([Storyboard18](https://www.storyboard18.com/amp/advertising/urban-company-turns-profitable-with-rs-28-5-crore-profit-in-fy25-ad-spend-surges-to-rs-207-crore-71108.htm))

## Open item to settle before launch

At payment time, the payment service credits the worker the amount paid minus a **default 20%**
(`default-platform-fee-percent` in `payment-service.yml`), applied to the whole amount charged
including GST. The price estimate uses the **10%** per-service fee on service charges, and GST is
included in what the worker is credited. Decide the intended split (with your accountant for GST),
then align the payment service with the per-service rate before taking real money.
