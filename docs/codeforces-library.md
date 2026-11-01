# Embedding the Codeforces source

`CFSettings.toDataSource()` retains manager-mode defaults. Public readers can use
`CFSettings(contestId) { asManager = false }` with no credentials. Signed manager
requests remain available when credentials are provided.

`settings.createSource(...)` accepts an optional `CFHistoryStore`, API base URL,
request-pacing callback, polling interval, reload/failure callbacks, and bounded
request/history observations. `includeUnofficial` and `individualsOnly` are opt-in.
The source owns fetching, conversion and polling; consumers should apply
`addComputedData { autoFinalize = false }` then `calculateScoreboard(NORMAL)`.

## History contract

The store is scoped to one contest. Resources are `standings`, `submissions`, and
`hacks`. Standings is a JSON object replaced by the latest successful response;
the other resources are arrays upserted by original numeric ID. Missing records
and empty arrays never delete stored history. `merge` must atomically persist a
successful response before returning the merged JSON value. `read` must expose
committed state. The default store is in-memory; durable implementations belong
to the embedding application. The parser also retains raw submissions during its
lifetime, so incomplete responses cannot remove wrong-attempt deductions.

On subscription, saved history is emitted first if available. `onReload(true)`
marks this restored state, which must not be represented as freshly fetched.
`onReload(false)` runs after a complete reload's events have passed through the
synchronous downstream pipeline. Do not insert asynchronous buffering between the
source and a consumer that relies on that completion boundary. Upstream or store
failures invoke `onFailure` and retry after the configured interval; cancellation
propagates and closes the source HTTP client. A single source flow should have one
collector; share the resulting state downstream for multiple viewers.

## Metadata and eligibility

`RunInfo.customFields["codeforces.submission"]` contains the original typed
Codeforces submission payload. Synthetic hack runs carry `codeforces.hack` instead.
`ContestInfo.customFields["codeforces.standings"]` contains contest/problem metadata.
Participant fields are available under `TeamInfo.customFields["codeforces.party"]`
and `codeforces.participantType`. These fields survive computed-data transforms.
No credentials are placed in metadata.

With unofficial inclusion enabled, participants can be discovered from retained
submissions or hacks even when absent from standings. IDs for individuals are
case-insensitive handles; official metadata takes precedence over unofficial
metadata for the same identity. Practice/virtual entries are not included by this
option. System-testing phases remain running; exact phases are retained in the
Codeforces metadata. Embedding services decide when final polling is complete.

ICPC wrong-attempt penalties are inferred from official solved-problem results
when the API supplies enough information. Without such a result, public contest
IDs below 100000 default to 10 minutes (extended ICPC rules, including Educational
rounds), and Gym IDs default to 20 minutes. Custom Gym penalties become available
when a solved official result with wrong attempts exposes them. Queued
`SUBMITTED` submissions remain in progress until their verdict changes.

The `freshConnections` option closes a dedicated HTTP client after each request.
The shared `beforeRequest` callback can coordinate all source instances. The
callbacks must not log signed request URLs or credentials.
