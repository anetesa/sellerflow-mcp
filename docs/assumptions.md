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
