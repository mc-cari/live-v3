# Optional CLICS synchronization and native metadata

The standard CDS converter supports two independent query options on its CLICS API:

- `event-feed?x_live=true` adds an `x_live` envelope to each event, containing `feed_instance`. Contest, team and submission events also carry native `custom_fields`. Submission events include the native `judging` result, including in-progress judging data. Native metadata participates in event comparison, so progress can change without a change in the standard CLICS submission fields.
- `scoreboard?x_live=true` adds `x_live.feed_instance`, `x_live.watermark`, and the contest's native `custom_fields`. The watermark is the last event token emitted for exactly that calculated scoreboard state. Its state and watermark are captured together after generating the state's events.
- `scoreboard?include_unofficial=true` includes supported out-of-contest participants. It reuses calculated rows and the engine ranking comparator to export ranks and ties across those participants. It does not change contest eligibility, scoring, or the default scoreboard.

These options are available under the ordinary `/clics/api/contests/contest` route and versioned CLICS routes. Without the options, response shapes and default scoreboard eligibility remain standard. Native custom-field names and values belong to their ingestion plugin; the exporter does not interpret them. Extensions do not add persistence, browser contracts, friend selection, or service telemetry.

A feed-instance ID is unique to each generator lifetime. Tokens are opaque and meaningful only with that ID. Consumers can retain a published view while staging a replay, fetch a scoreboard, and apply events through its matching watermark before publishing a coherent replacement. Events after the watermark belong to a later view. Reject a feed/scoreboard instance mismatch and reconcile again after a restart. Upsert entities by resource and ID; omitted replay entities are not explicit deletions. An ID event without data explicitly deletes that entity.

The converter exposes its current in-memory history and calculated state. This extension is not a durable acquisition history or a reload transaction boundary. It cannot recover upstream updates never observed by the source.

The Codeforces settings additionally expose `baseUrl` (default `https://codeforces.com/api/`) and `includeUnofficial` (default `false`) through the standard source configuration. They make the standard converter usable with alternate API endpoints and supported unofficial participants without embedding a service.

Validation: `./gradlew :cds-converter:test :cds:plugins:codeforces:test :cds:core:test :cds:plugins:codeforces:apiCheck :cds:core:apiCheck`.
