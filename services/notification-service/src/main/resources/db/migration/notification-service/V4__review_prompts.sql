-- Asking customers to rate their job, which brings in more reviews:
--
--   PAYMENT_COMPLETED.CUSTOMER  the payment confirmation now ends with a request to rate the job;
--   REVIEW_REMINDER.CUSTOMER    new: one reminder, a day after payment, to a customer who has not
--                               rated the job yet (rating-review-service publishes ReviewReminder).
--
-- Only rows still holding the seeded text are updated (updated_by IS NULL): an admin's edit
-- wins. The new rows are inserted unless they already exist. NotificationTemplateSeedTest applies
-- this file after V2 and V3 and checks the result equals BuiltInTemplates.

UPDATE notification.notification_template SET subject = 'Payment received', body = 'Payment for {{bookingReference}} was successful. Your invoice is ready. How was your professional? Rate the job in the app; it helps others choose.', updated_at = now() WHERE id = 'PAYMENT_COMPLETED.CUSTOMER.PUSH' AND updated_by IS NULL;
UPDATE notification.notification_template SET subject = NULL, body = 'Payment for {{bookingReference}} was successful. Your invoice is ready. How was your professional? Rate the job in the app; it helps others choose.', updated_at = now() WHERE id = 'PAYMENT_COMPLETED.CUSTOMER.SMS' AND updated_by IS NULL;
UPDATE notification.notification_template SET subject = 'Payment received', body = 'Payment for {{bookingReference}} was successful. Your invoice is ready. How was your professional? Rate the job in the app; it helps others choose.', updated_at = now() WHERE id = 'PAYMENT_COMPLETED.CUSTOMER.EMAIL' AND updated_by IS NULL;
UPDATE notification.notification_template SET subject = 'Payment received', body = 'Payment for {{bookingReference}} was successful. Your invoice is ready. How was your professional? Rate the job in the app; it helps others choose.', updated_at = now() WHERE id = 'PAYMENT_COMPLETED.CUSTOMER.IN_APP' AND updated_by IS NULL;

INSERT INTO notification.notification_template (id, template_key, name, channel, subject, body, updated_at) VALUES
    ('REVIEW_REMINDER.CUSTOMER.PUSH', 'REVIEW_REMINDER.CUSTOMER', 'Review reminder (customer) — Push', 'PUSH', 'How was your service?', 'How did your recent Ji-Hajoor job go? Rate your professional in the app: it takes ten seconds and helps other customers and good professionals.', now()),
    ('REVIEW_REMINDER.CUSTOMER.SMS', 'REVIEW_REMINDER.CUSTOMER', 'Review reminder (customer) — SMS', 'SMS', NULL, 'How did your recent Ji-Hajoor job go? Rate your professional in the app: it takes ten seconds and helps other customers and good professionals.', now()),
    ('REVIEW_REMINDER.CUSTOMER.IN_APP', 'REVIEW_REMINDER.CUSTOMER', 'Review reminder (customer) — In-app', 'IN_APP', 'How was your service?', 'How did your recent Ji-Hajoor job go? Rate your professional in the app: it takes ten seconds and helps other customers and good professionals.', now())
ON CONFLICT (id) DO NOTHING;
