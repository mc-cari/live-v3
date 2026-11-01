# Codeforces engine service

Private JSON service for live-v3 Codeforces ingestion, scoring, friend projections, and durable raw history. Go consumers own their public transport and telemetry.

From this directory, run `./gradlew test installDist`. Start `build/install/live-v3-backend/bin/live-v3-backend` with `DATA_DIR`, `LISTEN_ADDR`, and `METRICS_LISTEN_ADDR` set as needed. Build the container from the fork root with `docker build -f services/codeforces-engine/Dockerfile .`.

The internal API exposes `POST /internal/v1/contests/{id}/activate` and `GET /internal/v1/contests/{id}/view?h=<base64url-handles>`. Optional `after` revision and `health` parameters long-poll for changed views. The listener belongs on a private network. Codeforces configuration uses `CF_API_BASE_URL`, `CF_MANAGER_MODE`, `CF_API_KEY`, and `CF_API_SECRET`.
