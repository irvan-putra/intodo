# Free hosting for an always-on chat bot: what the vendor pages say

Status: vendor pages read 2026-11-16. This is a set of candidates and the facts each page states. No candidate is shown to work end to end: ports, disk, billing and signup requirements were not all checked for any one option, and nothing was signed up for or deployed.

The bot profile assumed: a small long-running process that keeps an IMAP connection open, sends over ports 465 or 587, needs a disk that survives restarts, and needs no inbound traffic. Memory use of such a stack was about 22.5 MB idle on a laptop in an earlier test (2026-11-12; a one-off, not repeated on any host).

| Option | Page says | Not checked |
|---|---|---|
| Google Cloud free tier | "1 non-preemptible `e2-micro` VM instance per month" in us-west1, us-central1 or us-east1; "30 GB-months standard persistent disk"; "The Free Tier has no end date, but Google reserves the right to change the offering ... with 30 days' advance notice." Outbound TCP 587 and 465 are not restricted by Google; port 25 to external destinations is blocked. | Billing-account and card requirements for the free VM, current egress limits in practice, whether the disk persists across restarts as needed |
| Oracle Cloud Always Free | "For Always Free tenancies, this is equivalent to 2 OCPUs and 12 GB of memory" (Arm A1). Oracle says idle Always Free compute instances may be reclaimed using its utilization criteria over a 7-day period. An "out of host capacity" error means "a temporary lack of Always Free shapes". | Card requirement, outbound port rules, the exact current utilization thresholds, and whether a quiet bot would be classed idle |
| Render free web service | "spins down a Free web service that goes 15 minutes without receiving any inbound traffic"; free services have an ephemeral filesystem and cannot attach a persistent disk. | Other Render plan types |
| PythonAnywhere free account | "Free accounts' internet access goes via a proxy 'allowlist'" through an HTTP proxy; paid accounts have no such limit. | Whether any IMAP/SMTP host is on the allowlist |
| Fly.io | "New organizations use Pay As You Go pricing." | The price of any specific deployment, region or machine size; whether new accounts get any free credit |
| Vercel | Outbound SMTP is allowed except port 25. | Always-on processes: the page does not describe them, none verified |

Sources: https://docs.cloud.google.com/free/docs/free-cloud-features , https://docs.cloud.google.com/compute/docs/tutorials/sending-mail , https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm , https://render.com/docs/free , https://help.pythonanywhere.com/pages/403ForbiddenError/ , https://fly.io/docs/about/pricing/ , https://vercel.com/kb/guide/serverless-functions-and-smtp

Not looked at in this pass: Replit, Koyeb, or a machine you already own. Free-tier terms are the vendors' to change; re-read the page before relying on one.
