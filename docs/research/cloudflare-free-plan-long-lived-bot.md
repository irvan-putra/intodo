# Cloudflare Free plan and a long-lived chat bot

Status: limits re-read on 2026-11-16 from Cloudflare docs. Nothing deployed or tested. This lists constraints; it does not conclude that only one product could work.

## Limits read (Free plan)
- Workers: 100,000 requests per day, 10 ms CPU per request or cron run, 128 MB memory, 5 Cron Triggers per account. CPU time excludes time spent waiting on I/O, so a small CPU limit alone does not rule out services that mostly wait on the network. https://developers.cloudflare.com/workers/platform/limits/
- Outbound TCP from Workers through `connect()` with a STARTTLS option. https://developers.cloudflare.com/workers/runtime-apis/tcp-sockets/
- Durable Objects have a Hibernation WebSocket API that lets an object sleep while clients stay connected. Durable Objects list a WebSocket message size limit of 32 MiB. https://developers.cloudflare.com/durable-objects/best-practices/websockets/ , https://developers.cloudflare.com/durable-objects/platform/limits/
- Containers list Free as N/A and are included in the $5 per month Workers Paid plan. https://developers.cloudflare.com/containers/platform/pricing/

## Open questions
- Whether a bot that holds an outbound mail connection (IMAP or similar) can be kept alive on a Durable Object under Free plan limits. Not tested.
- Whether Workers, Durable Objects with WebSockets, scheduled polling, or Containers fits is not settled by these pages. A polling design avoids a held connection.
- A 2026-11-12 reading reported outbound port 25 as blocked; not re-checked.

## Related
Cloudflare D1 is covered in the free-storage note in this folder.
