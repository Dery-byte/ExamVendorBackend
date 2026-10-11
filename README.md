# Vendor portal

Your own portal for the exam portal's subscriptions: the list of client institutions, the payments they
make, and the signed keys that control their access. Each client's exam portal checks in here every
6 hours and installs its newest key by itself, so recording a payment is all it takes to renew a client.
Suspending a client from here works the same way.

Host it once, for all clients (it is not deployed per client).

## How it fits together

```
 Vendor portal (this app)                       Client's exam portal (one per institution)
 ─────────────────────────                      ─────────────────────────────────────────
 Record payment / suspend  ──► signs a new key
                                   ▲
                                   │  POST /api/v1/check-in  (every 6 h, Bearer <client token>)
                                   └───────────────────────  sends state, version, user counts
                                     answers with the newest key ──► portal checks the signature,
                                                                     installs it if newer
```

- Keys are signed with the **online** private key (`subscription-tool/keys-online/private.key`). The exam
  portal trusts it and the offline key (`subscription-tool/keys`), which never goes on a server and stays
  your emergency backup.
- A fake or compromised network path can't grant anything: the exam portal only installs keys with a valid
  signature, for its own institution, newer than the one it has.
- If this portal is down, client portals keep running on the key they already have.

## Run it

| Variable | Required | Meaning |
|---|---|---|
| `SIGNING_PRIVATE_KEY` | yes | Contents of `subscription-tool/keys-online/private.key` (one base64 line). Store it as a secret. |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | yes in production | MySQL, e.g. `jdbc:mysql://host:3306/vendor_portal`. Without them a local H2 file (`./data`) is used. |
| `VENDOR_ADMIN_EMAIL` | yes | Your email (comma-separate several); always added to `vendor_user` if missing. Everyone signs in with a code emailed to them; there are no passwords. |
| `MAILJET_SMTP_USERNAME`, `MAILJET_SMTP_PASSWORD` | yes | Mailjet API key and secret (the exam portal's SMTP username / password). Codes are sent through Mailjet's API over HTTPS. |
| `MAIL_FROM_ADDRESS` | recommended | A sender verified in Mailjet, ideally on your own domain (see *Email delivery*). |
| `SPRING_PROFILES_ACTIVE` | yes, `prod` | Without it the server runs the `dev` settings: sign-in codes printed in its log, cookies over http. |
| `PUBLIC_URL` | yes | This portal's https address, shown in the linking instructions. |
| `COOKIE_SECURE` | no | Defaults to `true`; set `false` only for plain-http local runs. |
| `PAYSTACK_SECRET_KEY` | for online payment | Your Paystack secret key (`sk_test_…` to try it, `sk_live_…` for real). Without it invoices can only be paid by hand. |
| `VENDOR_NAME` | no | Your business name on the public invoice pages. |
| `SILENT_AFTER_HOURS` | no | Flags clients that haven't checked in for this long (default 36). |
| `PORT` | no | Defaults to 8090. |

**On your own computer:** settings are split like the exam portal's: `application.properties` (shared),
`application-dev.properties` (your computer, the default) and `application-prod.properties` (the server), with
secrets in a git-ignored `.env` file (copy `.env.example`). Locally it uses a `vendor_portal` database on your
MySQL (created on first start), Mailjet for sign-in codes (also printed in the console), and the signing key from
`../subscription-tool/keys-online/private.key`. Start it with `run-local.cmd` or by running `VendorPortalApplication`
in IntelliJ, then open http://localhost:8090. To link your local exam portal use `http://localhost:8090`.

**Docker / Render:** build from the `Dockerfile` in this folder, set `SPRING_PROFILES_ACTIVE=prod` and the variables above.

## Who can sign in

Only the emails in the `vendor_user` table, each with a 6-digit code emailed to them (10 minutes, 5 tries),
like the exam portal's `developer_email`. Add or remove people directly in the database:

```sql
INSERT INTO vendor_user (email, name, created_at) VALUES ('name@example.com', 'Their Name', NOW());
DELETE FROM vendor_user WHERE email = 'name@example.com';
```

## Add a client

1. **Add client**: name, institution code (e.g. `UCC`, it can't change later), paid-until date, grace and read-only days.
2. Copy the **check-in token** shown once.
3. On the client's exam portal, either set `SUBSCRIPTION_VENDOR_URL` and `SUBSCRIPTION_VENDOR_TOKEN` (and
   `SUBSCRIPTION_INSTITUTION_ID` = the code), or sign in as the developer → **Subscription** →
   **Vendor portal link** and enter the address and token. It checks in at once.

After that: **Record a payment by hand** to renew (or let them pay online, below); **Suspend now** / **Lift suspension** for disputes. The client's
page shows its last check-in, whether it has picked up the newest key, its state, version and user counts.

## Revenue

**Revenue** in the menu: what clients have paid you.

- At a glance: this month (and last month), this year, unpaid invoices, renewals due in the next 30 days, all time.
- One row of filters: This month · Last 3 months · This year · Last 12 months · All time, or your own dates; one client;
  online (Paystack) or recorded by hand. Everything below follows it.
- Received per month (per year for ranges over three years), with a table view; by client (share of the period, what
  each still owes); by payment method; the payments themselves. **Export CSV** downloads exactly what is shown.
- Amounts are kept per currency: GHS and USD are never added together.

## Online payment (Paystack)

1. Set `PAYSTACK_SECRET_KEY`. In the Paystack dashboard → Settings → API Keys & Webhooks, set the webhook URL to
   `<PUBLIC_URL>/api/v1/paystack/webhook`.
2. Give a client a **yearly price** (Edit details & terms → Billing). 30 days before its paid date an invoice is
   raised automatically; you can also **Raise an invoice** yourself on the client's page (one open invoice at a time).
3. The client's portal shows the invoice at its next check-in: the Super Admin (and Admins, if chosen) get a
   **Pay online** button, and the reminder emails carry the link. Anyone with the link can pay; it shows only the
   institution's name and the amount.
4. They pay on Paystack (card, Mobile Money or bank transfer). The payment counts only once Paystack's own API confirms
   it, for exactly the invoiced amount, and only once (the webhook and the payer's return can both arrive). Then the
   paid date moves on, a new key is issued, and the portal installs it at its next check-in, or at once when the
   Super Admin presses **"I've paid, check now"**.

A payment for the wrong amount isn't counted; it is logged on the client's page for you to sort out.

## Email delivery

Codes go through Mailjet's Send API. Every request is logged on the server, so you can see what happened:

- `[VENDOR-LOGIN] Code emailed to x (Mailjet message …)`: Mailjet accepted it. Not in the inbox? Look in **Spam / Promotions**.
- `[VENDOR-LOGIN] Code NOT emailed to x: …`: Mailjet's reason (wrong key, sender not verified …).
- `[VENDOR-LOGIN] Code requested for x, which is not in vendor_user`: that address can't sign in; add it.

**Account → Send me a test email** sends one now and shows Mailjet's answer.

**Getting codes into the inbox:** a `@gmail.com` sender sent through Mailjet can't pass Gmail's checks (SPF/DKIM), so Gmail
often files it as Spam. Send from your own domain instead, e.g. `optimusinforservice@elygrad.com`:

1. Mailjet → *Account settings → Senders & domains → Add domain*: `elygrad.com`.
2. At your DNS host (Namecheap), change the one SPF TXT record on `elygrad.com` to
   `v=spf1 include:spf.efwd.registrar-servers.com include:spf.mailjet.com ~all`.
3. Add the DKIM TXT record Mailjet shows, on `mailjet._domainkey.elygrad.com`.
4. When Mailjet shows SPF and DKIM as OK, validate the sender `optimusinforservice@elygrad.com` (the confirmation email
   reaches you through your Namecheap email forwarding).
5. Set `MAIL_FROM_ADDRESS=optimusinforservice@elygrad.com` on the vendor portal (and the exam portal, which has the same issue).

## Deploying on Render

1. Web service from this folder's `Dockerfile`.
2. **Environment** tab of *this* service (or an Environment Group linked to it), exact names:
   `SPRING_PROFILES_ACTIVE=prod`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SIGNING_PRIVATE_KEY` (the one 64-character
   line from `subscription-tool/keys-online/private.key`, starting `MC4CAQAw`), `MAILJET_SMTP_USERNAME`,
   `MAILJET_SMTP_PASSWORD`, `MAIL_FROM_ADDRESS`, `VENDOR_ADMIN_EMAIL`, `PAYSTACK_SECRET_KEY`.
   `PUBLIC_URL` is optional on Render: without it the portal uses Render's own `RENDER_EXTERNAL_URL`.
   Set it if you use your own domain.
3. **Save, rebuild and deploy.** The first lines of the log are a `[CONFIG]` summary: each setting found or `MISSING`.
