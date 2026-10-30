# Ji-Hajoor Admin — Staff Guide

This guide is for **Ji-Hajoor staff** who run the marketplace day to day in **Ji-Hajoor Admin**.

**Where:** [admin.jihajoor.com](https://admin.jihajoor.com). Staff join by **invitation** only: a
Super Admin invites you by email, and you set your password from the link (at least 10 characters,
with a letter and a digit, and not built from your name or email).

---

## Roles: who sees what

| Role | What it is for | Pages |
|---|---|---|
| **Super Admin** | Owns the platform | Everything, plus System Configuration and editing Dispatch Rules |
| **Admin** | Runs operations | Dashboard, User Management, Workers, Verification Queue, Agencies, Service Categories, Pricing Configuration, Booking Management, Dispatch Rules (view), Payments & Refunds, Complaints, Review Moderation, Coupon Management, Notification Templates, Report Generation, Audit Logs |
| **Finance Admin** | Money | Payments & Refunds, Report Generation |
| **Dispatcher** | Matching | Booking Management, Dispatch Rules (view) |
| **Support Agent** | Customers and workers | Booking Management, Complaints, Review Moderation |
| **Agency administrator** | One agency's team | My agency pages only (see the [Agency Guide](AGENCY_GUIDE.md)) |

Give each person the smallest role that lets them do their job. Every change is recorded in the
**Audit Logs**.

## Daily checklist

1. **Verification Queue:** approve or reject new workers' documents, then record the background
   check result. Approved workers start receiving offers.
2. **Agencies:** decide new agency applications and set each agency's share (0–50%).
3. **Complaints:** answer new complaints; approve refunds where they are justified.
4. **Review Moderation:** publish or remove reviews held for checking.
5. **Booking Management:** look for bookings stuck waiting for a worker.

## Supply: workers and agencies

- **Workers** lists every worker with verification status, rating and agency. Verify a worker's
  **bank account** here before their first payout.
- **Verification Queue:** open a worker, check each document (Government ID, address proof, skill
  certificate), then **Approve** or **Reject** with a reason the worker will see. After approval,
  the worker waits for the **background check**; record **Passed** or **Failed** with a note.
- **Agencies:** approve or reject applications (the reason is shown to the applicant), set the
  agency share, edit the area and categories, and see each agency's team.

## Catalogue, pricing and promotions

- **Service Categories:** add or edit categories and services, their starting price, duration and
  whether **Emergency** is offered. Icon links must be `https://` or a path on this site.
- **Pricing Configuration:** for each service, set the base price, per-km travel rate and cap,
  night and weekend surcharges, **platform fee rate**, tax rate, emergency and surge multipliers.
  The starter pricing uses a 10% platform fee and 18% GST.
- **Coupon Management:** create **flat** or **percentage** coupons with a minimum order value,
  maximum discount, validity dates, a per-customer limit and a total limit. Use them for launches,
  referrals and win-back campaigns.

## Bookings and dispatch

- **Booking Management:** find any booking by reference, customer or worker, and see its full
  timeline.
- **Dispatch Rules:** the weights the engine uses to choose workers (distance, rating, acceptance
  history and so on) and its timeouts. Only a Super Admin can change them.

## Money

- **Payments & Refunds:** every payment and its Razorpay reference; issue full or partial refunds.
  Each payment is credited once, however often Razorpay retries.
- Worker **settlement requests** (payouts to bank) are handled here, once the worker's bank account
  is verified on the Workers page.
- **Report Generation:** booking, revenue and settlement reports for a date range, as CSV
  or PDF.

## Customers and quality

- **Complaints:** each complaint is linked to its booking. Investigate, respond, and approve a refund
  when needed; the refund goes back to the original payment method.
- **Review Moderation:** reviews that the fraud checks hold (for example from a brand-new
  account, a burst from one network address, or a rating far from the worker's usual) wait here.
  **Publish** genuine ones; **Remove** abusive or fake ones with a reason. A removed review cannot
  be published again.
- **Notification Templates:** edit the push, SMS, email and in-app wording for each event, including
  the **review reminder** sent once, a day after an unrated job, and the payment receipt that asks
  for a rating.

## Platform

- **User Management:** find accounts, suspend or restore them, and invite staff with a role.
- **Audit Logs:** who changed what, and when.
- **System Configuration** (Super Admin): platform-wide settings, such as the app addresses used in
  links.

## Security rules for staff

- Never share your password or sign-in codes, and never sign in on a shared computer without
  signing out.
- Ji-Hajoor will never ask you for your password by phone, chat or email.
- Report anything unusual (unexpected refunds, a review burst, a strange login) to the Super Admin
  straight away.

---

© 2026 Ji-Hajoor. Verified Help. Anytime. Anywhere. Powered by
[Condigence LLP](https://www.condigence.com/).
