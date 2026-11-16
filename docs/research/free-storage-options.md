# Free storage options for a local-first app

Research note, checked 16 Nov 2026 against each provider's official pages. Free tiers change without notice and none promise forever. No accounts were created for this note, nothing was deployed or connected, and nothing here commits the project to any provider.

## The short version

- DeltaChat stays the encrypted transport. Every option below is an optional extra for the native app or a relay, never a requirement.
- Best database candidates: Turso, Neon and Cloudflare D1. Firebase Firestore is the Android-native pick.
- Public GitHub works as an encrypted backup and mirror, not as a live database.
- Oracle Cloud's Always Free tier includes a free ARM VM (2 OCPU, 12 GB RAM) plus 200 GB of block volume - enough to self-host a small relay; it asks for a card and reclaims idle machines. The Google Cloud always-free VM is smaller (one e2-micro per month).

## The webxdc boundary

The official webxdc spec requires messengers to deny mini-apps all internet access, so every update rides encrypted DeltaChat messages. These databases and servers are options for the native app or a separate relay process, never for code inside the `.xdc`.

## Public GitHub as encrypted storage

Usable, with sharp edges. It fits as a durable encrypted mirror that can be cloned anywhere, on top of the repo this project already publishes.

- Everything in a public repo is world-readable. Ciphertext protects the content, not the metadata: file names, sizes, commit times and history stay visible.
- Git never forgets. Old ciphertext survives in history and in forks even after deletion, and rotating a key only protects future commits.
- Rate limits make it a poor live database: 5,000 API requests per hour authenticated, 60 per hour unauthenticated, and a secondary limit of about 500 content-generating requests per hour. GitHub recommends repositories stay under 1 GB (5 GB strongly recommended as an upper bound) and can contact accounts over excessive automated use.
- Writes need credentials. A token embedded in an app would leak, especially with public source code; writes belong on a machine the owner controls or behind credentials each user supplies.

Verdict: good for encrypted backup and portability, poor as a live sync database. A private repo instead of a public one also hides the metadata from the public - though never from GitHub itself or from anyone given access - and is still free.

## Recommended relays

Free tiers of the four best-fit databases, checked 16 Nov 2026:

| | Turso | Neon | Cloudflare D1 | Firebase Firestore |
| --- | --- | --- | --- | --- |
| Free allowance | 5 GB storage, 500M rows read and 10M rows written per month, 3 GB monthly syncs, 100 databases | 1 GB storage per project (20 GB total), 100 CU-hours per project per month, 5 GB transfer per project per month, up to 100 projects | 5 GB storage total, 5M rows read and 100K rows written per day, 10 databases, plus 100K Worker requests per day | 1 GiB storage, 50K reads and 20K writes per day, 10 GiB egress per month |
| When idle | Idle databases are just stored data | Sleeps after 5 minutes, wakes on demand; cannot be disabled | Scales to zero; no pause | No pause |
| Model | SQLite (libSQL) | Postgres | SQLite at the edge, behind Workers | Document NoSQL |
| Fit for this app | Strongest. SQLite everywhere matches the local-first core, and embedded replicas sync devices. | Good relay. Serverless Postgres wakes fast enough for occasional widget sync. | Good relay. Pairs with Workers for a small sync API; the API is vendor-specific. | The Android-native pick. Offline cache and free FCM push; server functions need the paid plan. |

Cards: Turso's pricing page says "no credit card required" for the free plan, and Firebase's Spark plan says "no payment method needed". Card requirements for Neon and Cloudflare were not rechecked for this note.

## Also considered

Free tiers of the remaining databases on the shortlist, checked 16 Nov 2026:

| | Supabase | MongoDB Atlas M0 | Upstash Redis | Qdrant Cloud |
| --- | --- | --- | --- | --- |
| Free allowance | 500 MB database, 5 GB egress, 1 GB file storage, 2 active projects | 512 MB, shared RAM and vCPU, free forever | 250 MB, 500K commands per month, 10 GB bandwidth, 1 database on a free account | 1 GB RAM, 4 GB disk |
| When idle | Paused after 1 week without activity | No pause | No pause | Suspended after 1 week unused, deleted after 4 weeks |
| Model | Postgres with auth and realtime | Document NoSQL | Redis cache and queue | Vector search |
| Fit for this app | Rich but pauses. Everything in one box, yet a 1-week pause breaks an always-on relay. | Durable but tiny. 512 MB suits a small encrypted ops log. | Sidecar only: rate limiting or queues, not the primary store. | Only for semantic search over items. Not a general store. |

Qdrant explicitly states no credit card is needed for a free cluster.

## Free servers and cloud accounts

- **Oracle Cloud Always Free** - an ARM VM up to 2 OCPU and 12 GB RAM for Always Free tenancies, two AMD micro VMs, 200 GB block volume storage, and two autonomous databases. A card is required for identity checks, one account per person, idle VMs can be reclaimed, and accounts idle 30 days risk closure. Ongoing.
- **Google Cloud** - $300 credit for new customers, then always-free monthly amounts including one e2-micro VM per month and 5 GB-months of standard storage. Mixed.
- **Microsoft Azure** - $200 credit for 30 days, free monthly amounts of 20+ services for 12 months for new customers, and 65+ always-free services. Mixed.
- **AWS** - new accounts get $100 in credits plus up to $100 more earned by exploring services. The free plan closes on its own 6 months after opening or when the credits run out, whichever comes first; the old 12-month free tier no longer applies to new accounts. 6-month credits.
- **Render** - 750 free instance hours per month; free web services sleep after 15 idle minutes and take about a minute to wake, local files are lost on every sleep, and free Postgres expires 30 days after creation. Ongoing, sleeps.
- **Fly.io** - stopped selling plans to new customers in October 2024; pay-as-you-go only. None.

## Choices kept open

Nothing here needs an answer now. The open forks:

1. Git storage: public repo with encrypted payloads, a private repo that also hides metadata, or no Git storage at all.
2. Whether to add an always-online relay at all. DeltaChat alone may already cover the app's devices and groups.
3. If a relay: a managed database such as Turso, Neon or D1, the Android-native Firebase route, or a self-hosted relay on Oracle.
4. Whether card-required providers such as Oracle are acceptable, or no-card providers only.

## Sources

- [Turso pricing](https://turso.tech/pricing) · [Neon free plan limits](https://neon.com/faqs/free-plan-limits-and-quotas) · [Cloudflare D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/) · [Cloudflare Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/)
- [Firebase pricing](https://firebase.google.com/pricing) · [Supabase pricing](https://supabase.com/pricing) · [MongoDB Atlas pricing](https://www.mongodb.com/pricing) · [Upstash Redis pricing](https://upstash.com/pricing/redis) · [Qdrant Cloud clusters](https://qdrant.tech/documentation/cloud/create-cluster/)
- [Oracle Cloud Free Tier](https://www.oracle.com/cloud/free/) · [Oracle Always Free resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm) · [Google Cloud free tier](https://cloud.google.com/free) · [AWS Free Tier](https://aws.amazon.com/free/) · [Azure free account](https://azure.microsoft.com/en-us/pricing/purchase-options/azure-account) · [Render free instances](https://render.com/docs/free) · [Fly.io discontinued plans](https://docs.fly.io/about/discontinued-plans)
- [GitHub API rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api) · [GitHub size limits](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github) · [webxdc messenger spec](https://webxdc.org/docs/spec/messenger.html)
