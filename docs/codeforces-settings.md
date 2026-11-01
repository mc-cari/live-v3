# Codeforces source settings

The Codeforces plugin uses the shared `FullReloadContestDataSource` flow with a
five-second reload interval. Finished contests emit a temporary RUNNING status,
then their runs, then OVER, so auto-finalization receives the results first.

- `contestId`: the contest to load.
- `asManager`: defaults to true and requires nonempty `apiKey` and `apiSecret`.
  Set false for anonymous public requests; those send only `contestId`.
- `baseUrl`: defaults to `https://codeforces.com/api/`; a local server can supply
  captured API responses for tests.
- `includeUnofficial`: defaults to false; true enables supported out-of-competition
  participants. Teams remain eligible under the parser's participant rules.

The source keeps ordinary parser state during polling and uses live-v3's existing
reload and error handling. It exposes no custom raw-history store or reload,
request, failure, or history callbacks. Go consumes the converter's CLICS API.
