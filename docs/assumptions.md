# Assumptions

Decisions taken where the official documentation was ambiguous or not yet checked. Each entry says what we
assume, why, and how to verify it. Update the entry when an assumption is confirmed or proven wrong.

## A1. Environment endpoints (Phase 0)

| Service | Sandbox | Production | Source |
|---|---|---|---|
| Allegro REST API | `https://api.allegro.pl.allegrosandbox.pl` | `https://api.allegro.pl` | developer.allegro.pl |
| Allegro OAuth | `https://allegro.pl.allegrosandbox.pl/auth/oauth` | `https://allegro.pl/auth/oauth` | developer.allegro.pl |
| InPost ShipX | `https://sandbox-api-shipx-pl.easypack24.net` | `https://api-shipx-pl.easypack24.net` | ShipX documentation (dokumentacja-inpost.atlassian.net) |
| KSeF 2.0 | not set yet | not set yet | to be taken from the Ministry of Finance OpenAPI document in Phase 3 |

Third-party sources disagree on the KSeF 2.0 test host (`ksef-test.mf.gov.pl` vs `api-test.ksef.mf.gov.pl`),
so no KSeF URL is hard-coded until it is checked against the official OpenAPI specification.

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
