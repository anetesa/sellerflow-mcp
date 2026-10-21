# SellerFlow MCP

[![CI](https://github.com/anetesa/sellerflow-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/anetesa/sellerflow-mcp/actions/workflows/ci.yml)

An open-source [Model Context Protocol](https://modelcontextprotocol.io) server that lets an AI agent run the daily
fulfillment cycle of a Polish online seller:

**Allegro order → InPost shipment and label → structured invoice in KSeF**

> "Process yesterday's paid Allegro orders: create InPost locker shipments, give me the labels, and issue
> invoices in KSeF for the buyers who asked for one."

> **Status: early development.** Phase 0 (project skeleton) is done; the Allegro, InPost and KSeF integrations
> are being built phase by phase. Sandbox environments are the default.

## Safety model

- **Sandbox by default.** Production is used only with `SELLERFLOW_ENV=prod`.
- **Human in the loop.** Every tool that changes something returns a preview first; the real action needs
  `dry_run=false` and the `confirm_token` from that preview.
- **Idempotent.** An order never gets two shipments or two invoices.
- **No secrets in files or logs.** Client secrets and tokens come from environment variables; stored tokens
  are encrypted.

## Quick start (sandbox)

Requirements: JDK 21.

```bash
./gradlew :mcp-server:installDist
mcp-server/build/install/sellerflow/bin/sellerflow serve   # MCP over stdio
```

Try it with [MCP Inspector](https://github.com/modelcontextprotocol/inspector):

```bash
npx @modelcontextprotocol/inspector --cli mcp-server/build/install/sellerflow/bin/sellerflow serve --method tools/list
```

Configuration: copy `sellerflow.example.toml` to `sellerflow.toml`; secrets go to environment variables.

## Modules

| Module | Responsibility |
|---|---|
| `core` | Domain model (orders, shipments, invoices, money, NIP), errors, configuration |
| `storage` | SQLite: encrypted tokens, order ↔ shipment ↔ invoice mapping, audit log, confirm tokens |
| `allegro` | Allegro REST API: OAuth device flow, orders, buyer invoice data |
| `inpost` | InPost ShipX: points, quotes, shipments, labels |
| `ksef` | KSeF 2.0: authentication, sending invoices, status, incoming invoices |
| `invoice` | Order → FA XML, VAT, numbering, XSD validation |
| `workflow` | End-to-end fulfillment: idempotent and resumable |
| `mcp-server` | MCP tools, resources and prompts; stdio and Streamable HTTP |

Dependencies point inward: the workflow sees services through interfaces from `core`, so every client can be
replaced with a fake in tests.

## License

[MIT](LICENSE)
