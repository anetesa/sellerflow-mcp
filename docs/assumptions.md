# Assumptions

Decisions taken where the official documentation was ambiguous or not yet checked. Each entry says what we
assume, why, and how to verify it. Update the entry when an assumption is confirmed or proven wrong.

## A1. Environment endpoints (Phase 0)

| Service | Sandbox | Production | Source |
|---|---|---|---|
| Allegro REST API | `https://api.allegro.pl.allegrosandbox.pl` | `https://api.allegro.pl` | developer.allegro.pl |
| Allegro OAuth | `https://allegro.pl.allegrosandbox.pl/auth/oauth` | `https://allegro.pl/auth/oauth` | developer.allegro.pl |
| InPost ShipX | `https://sandbox-api-shipx-pl.easypack24.net` | `https://api-shipx-pl.easypack24.net` | ShipX documentation (dokumentacja-inpost.atlassian.net) |
| KSeF 2.0 | `https://api-test.ksef.mf.gov.pl/v2` (TEST) | `https://api.ksef.mf.gov.pl/v2` | CIRFMF/ksef-docs `srodowiska.md` and `open-api.json` (checked 2026-11-17) |

The sandbox maps to KSeF's TEST environment, which accepts self-signed certificates and shares its data between
integrators (use random NIPs only). KSeF also has DEMO (pre-production); SellerFlow does not use it yet.

## A2. Production is opt-in through the environment only (Phase 0)

`SELLERFLOW_ENV=prod` is the only switch to production; the config file cannot select it. A copied or
committed config therefore cannot point a test setup at real orders and legally binding invoices.

## A3. MCP Kotlin SDK 0.15.0 (Phase 0)

The SDK provides stdio and Streamable HTTP server transports (`StdioServerTransport`,
`Application.mcpStreamableHttp`), so the project stays on Kotlin. The in-memory `ChannelTransport` used in
tests is marked `@ExperimentalMcpApi`; it is opted into in test code only.

## A4. Allegro requires the app's User-Agent (Phase 1)

The developer portal warns that API calls without a compliant User-Agent get the API key blocked. The format its
generator produces is `Name/version (+https://where-the-app-is-described)`. `AllegroHttp` refuses to exist without
`SELLERFLOW_ALLEGRO_USER_AGENT` in that format and sets the header on every request, OAuth included. Verified
against the sandbox on 2026-11-17: client credentials and the device flow both answered 200 with it.

## A5. Reading checkout forms (Phase 1)

Taken from the "Jak obsługiwać zamówienia" tutorial; the sandbox had no orders to check them against yet.

- A checkout form has no creation time. The order time is the earliest `lineItems[].boughtAt`, falling back to
  `updatedAt`.
- An order is ready to ship when `status` is `READY_FOR_PROCESSING` and `fulfillment.status` is not one of `SENT`,
  `READY_FOR_PICKUP`, `PICKED_UP` or `RETURNED`.
- The delivery method is recognised by its name: "InPost" or "Paczkomat" with a pickup point is a locker, without
  one a courier. Allegro also has method ids; switch to them once real InPost method ids are seen in the sandbox.
- The buyer's NIP is `invoice.address.company.taxId`. A NIP that fails its checksum is dropped, so the invoice step
  asks the seller instead of issuing a wrong invoice.

Check all four on the first sandbox order and update this entry.

## A6. InPost ShipX, as seen in the sandbox (Phase 2)

Checked against the sandbox on 2026-11-17 (organization 7281, locker KRA010):

- `POST /v1/organizations/{id}/shipments` answers at once with status `created` and no tracking number; ShipX then
  prepares offers and buys one in the background.
- On a prepaid account with no funds the purchase fails: the shipment stays `offer_selected` and `transactions`
  holds `{"status":"failure","details":{"error":"debt_collection"}}`. The label endpoint then answers 400
  `invalid_action` / `shipment_status_incorrect`, and so does cancelling. SellerFlow reports this as "the InPost
  account has no funds".
- `GET /v1/points/{name}` works on the ShipX host without the token. An unknown name answers HTTP 200 with
  `{"status":404,"error":"resource_not_found"}`, so the body, not the status code, decides.
- The sandbox has its own lockers (KRA010 exists there, KRA010M does not), so production locker names from Allegro
  orders may not exist in the sandbox.
- The label is `GET /v1/shipments/{id}/label?format=Pdf&type=A6` (or `normal` for A4); not yet seen succeeding,
  because the sandbox account has no funds. Verify it once the account is topped up or switched to invoice billing.

## A7. KSeF 2.0 login (Phase 3)

From CIRFMF/ksef-docs (`uwierzytelnianie.md`, `tokeny-ksef.md`, `open-api.json`) and the official ksef-client-java,
checked against KSeF TEST on 2026-11-17:

- SellerFlow logs in with a KSeF token, which a seller generates in the Ministry's taxpayer application. The string
  `{token}|{challenge.timestampMs}` is encrypted with RSA-OAEP (SHA-256, MGF1-SHA-256) under the
  `KsefTokenEncryption` key from `GET /security/public-key-certificates` and sent to `POST /auth/ksef-token` with that
  key's `publicKeyId`.
- The login is asynchronous: `GET /auth/{referenceNumber}` with the operation token answers code 100 while it is
  checked and 200 when done; then `POST /auth/token/redeem` gives the access token (minutes) and the refresh token
  (up to 7 days), and `POST /auth/token/refresh` renews the access token.
- The first KSeF token can only be issued after a XAdES login. In TEST a self-signed certificate with
  `serialNumber=TINPL-{NIP}` is accepted; the integration test signs with EU DSS (XAdES-BASELINE-B, enveloped), as
  the official Java client does. DSS and BouncyCastle are test dependencies only.
