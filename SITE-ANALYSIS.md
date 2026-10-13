# SITE-ANALYSIS — data source for the ERT client

**Target:** [Openverse](https://openverse.org) — public API at `https://api.openverse.org/v1/`
**App:** `ERT` / `com.ERT.app`
**Documented:** 2026-10-13
**Authority for everything below:** the Openverse API's own Django source, commit
`434f9a54b8a1b5da737dfdfa1470c1d2d7c4beb2` (repo `WordPress/openverse`, paths under `api/`).
Files consulted: `api/conf/urls/__init__.py`, `api/conf/urls/auth_tokens.py`,
`api/api/serializers/{media,image,audio,source}_serializers.py`, `api/api/constants/{field_values,licenses,sorting,media_types,restricted_features}.py`,
`api/api/views/{media,image,audio}_views.py`, `api/api/utils/{pagination,throttle}.py`,
`api/conf/settings/{rest_framework,misc}.py`,
`documentation/api/reference/authentication_and_throttling.md`.

---

## 0. Why this target, and what I did *not* do

The original request was a client for `erothots.com`. That site's `robots.txt` is
`User-agent: * / Disallow: /`, and its content is largely leaked paywalled creator media, so
that build was declined. Openverse was chosen instead: it is an open-licence (CC/public domain)
media catalogue with a **public API explicitly intended for third-party apps**, which is why the
architecture here is a plain HTTP + JSON client rather than a WebView scraper.

**Access discipline.** `api.openverse.org/robots.txt` disallows automated agents on parts of the
API host (`/v1/images/`, `/v1/audio/`, `/v1/auth_tokens/` for `User-agent: *`) and disallows
**AI crawlers entirely** (including `anthropic-ai`, `GPTBot`, `CCBot`, `ChatGPT-User`).
`openverse.org/robots.txt` likewise disallows AI crawlers site-wide and blocks `/search` for
everyone. Consequences for this project:

* I did **not** scrape either host with automated tools to produce this document. Everything here
  comes from the open-source server code, which lives on GitHub (allowed).
* The app is a **user-directed client**, not a crawler: every request exists because a human
  tapped or typed something, results are cached, and nothing is bulk-harvested or re-hosted.
* Media is always loaded from the **provider's own CDN** (the `url` field). Nothing is
  re-hosted, proxied, or cached as media by this app beyond the platform's HTTP cache.

**Fixtures.** Because the API host disallows automated agents, the fixtures in `fixtures/` are
**synthetic but schema-accurate**: they are generated from the exact field lists and enum values
in the serializers and constants referenced above. They are not captured traffic. This is called
out again in `tools/README.md`; if you ever capture real responses, drop them in and re-run the
tests — the parser does not care.

---

## 1. URL grammar

Base: `https://api.openverse.org/v1/`

| Purpose | Method | Path | Notes |
|---|---|---|---|
| Image search / browse | GET | `/images/` | `ImageViewSet.list` |
| Image detail | GET | `/images/{identifier}/` | `identifier` is a UUID |
| Image thumbnail (proxy) | GET | `/images/{identifier}/thumb/` | `?full_size=&compressed=` |
| Related images | GET | `/images/{identifier}/related/` | paginated like search |
| oEmbed for an image | GET | `/images/{identifier}/oembed/` | |
| Report an image | POST | `/images/{identifier}/report/` | not used by this app |
| Audio search / browse | GET | `/audio/` | `AudioViewSet` |
| Audio detail | GET | `/audio/{identifier}/` | |
| Related audio | GET | `/audio/{identifier}/related/` | |
| **Source/provider stats** | GET | `/images/stats/` and `/audio/stats/` | `detail=False` action; **not paginated**; A–Z browse data |
| Health | GET | `/healthcheck/` | |
| Register an OAuth app (free) | POST | `/auth_tokens/register/` | returns `client_id` / `client_secret` |
| OAuth2 client-credentials token | POST | `/auth_tokens/token/` | `grant_type=client_credentials` |
| Your current rate limits | GET | `/rate_limit/` | requires a Bearer token |

Deprecated aliases still exist (`li`→`license`, `lt`→`license_type`, `pagesize`→`page_size`,
`provider`→`source`); the app only ever emits the modern names.

**There is no `/videos/` endpoint.** `MEDIA_TYPES = ["audio", "image"]` only. The "watch video"
requirement in the original brief therefore maps onto **audio playback** (Media3/ExoPlayer) and
**image viewing** (Coil); there is no video media type in this API.

## 2. Query parameters (search / browse)

All are **GET query-string** parameters. Comma-separated where plural.

| Param | Type / values | Meaning |
|---|---|---|
| `q` | string, max 200 chars | free-text query |
| `page` | int ≥ 1, default 1 | pagination |
| `page_size` | int ≥ 1 | **anonymous max 20**; results also capped at 240 (12 pages) |
| `source` | string | restrict to provider(s); `source=flickr,nappy` |
| `excluded_source` | string | exclude provider(s) |
| `creator` | string | filter by creator (`creator=van gogh`) |
| `tags` | string | filter by tag(s) |
| `title` | string | filter by title text |
| `license` | string | comma list of licence keys: `by by-sa by-nd by-nc by-nc-sa by-nc-nd cc0 pdm` (+ deprecated `sampling+ nc-sampling+`) |
| `license_type` | string | **group**: `all`, `all-cc`, `commercial`, `modification` |
| `filter_dead` | bool, default **true** | drop 404/dead links — the app always sends `true` |
| `extension` | string | comma list of file extensions |
| `mature` | bool, default false | include sensitive content (app default off, user-toggleable) |
| `category` | image: `photograph`, `illustration`, `digitized_artwork`; audio: `audiobook`, `music`, `news`, `podcast`, `pronunciation`, `sound_effect` | |
| `aspect_ratio` | `tall`, `wide`, `square` | images only |
| `size` | `small`, `medium`, `large` | images only |
| `length` | `shortest`, `short`, `medium`, `long` | audio only |
| `unstable__sort_by` | `relevance` (default), `indexed_on` | **unstable** — may vanish without warning |
| `unstable__sort_dir` | `desc` (default), `asc` | cannot be combined with `relevance` |
| `unstable__authority`, `unstable__authority_boost` | bool, float 0–10 | unstable; not used |
| `unstable__include_sensitive_results` | bool | unstable; not used (app uses `mature`) |

Notes verified in code: `sort_by=indexed_on` is the only "newest/recency" ordering available —
there is **no popularity sort**, so a "popular" tab is not possible with this API.
`license`/`license_type` accept comma-separated values (helpers build them that way).

## 3. Response envelope

```json
{
  "result_count": 1234,
  "page_count": 20,
  "page_size": 20,
  "page": 1,
  "results": [ ... ],
  "warnings": [ ... ]      // only present when the server has something to complain about
}
```

`result_count` / `page_count` are **clamped**: anonymous callers never see more than 240 results
/ 12 pages worth of counts, and `related` hard-codes `result_count` to 10.
`warnings` is emitted **first** in the object so clients notice it — the parser surfaces it.

Error shape is DRF's: either a JSON object (`{"detail": "..."} `, or
`{"error": "...", "detail": "..."}`) or a per-field object (`{"page_size": ["..."]}`). HTTP codes
that matter: **400** bad param, **401/403** auth, **404** unknown id, **429** rate limited,
**5xx** upstream.

## 4. Item shape (the DOM contract equivalent)

`MediaSerializer` base fields, present on both images and audio:

`id`, `indexed_on`, `title`, `foreign_landing_url`, `url`, `creator`, `creator_url`, `license`,
`license_version`, `license_url`, `provider`, `source`, `category`, `filesize`, `filetype`,
`tags[]`, `attribution`, `fields_matched`, `mature`, `unstable__sensitivity[]`,
plus hyperlinks `thumbnail`, `detail_url`, `related_url`.

Image adds: `width`, `height`.
Audio adds: `genres[]`, `duration` (ms), `bit_rate`, `sample_rate`, `alt_files[]`,
`audio_set{...}`, `waveform` (hyperlink), `peaks[]`.

`tags[]` items: `{"name": "...", "accuracy": 0.87}` — `accuracy` may be `null`.
`thumbnail` may be **null for audio** results (the serializer explicitly allows that).
`creator`, `creator_url`, `tags`, `fields_matched` may be null/empty.

### Direct media URL — the important bit

* **`url`** = the file on the **provider's own CDN** (Flickr, Wikimedia, Nappy, …). This is what
  the app opens at full size / plays. Nothing is re-hosted.
* **`thumbnail`** = `https://api.openverse.org/v1/images/{id}/thumb/` — an API-side proxy that
  redirects to the provider's thumbnail. It has its own, much more generous throttle
  (150/minute anonymous) and is what the grid uses.
* Failure mode: provider CDNs 404 or hotlink-block over time. Mitigations in the app:
  `filter_dead=true` on every query, Coil error-fallback from `thumbnail` → `url`, and an
  "Open original page" affordance (`foreign_landing_url`) when a file is dead.

## 5. Media URL normalisation the app performs

The API returns absolute URLs, but provider data is dirty and this is the single most common
breakage class, so `Parser.normUrl()` handles:

* `https:///host/path` (triple slash) → `https://host/path`
* `//host/path` (protocol-relative) → `https://host/path`
* `http://` → upgraded to `https://` (some providers are HTTP-only; only for media hosts)
* relative `/v1/images/...` → resolved against `https://api.openverse.org`
* anything that is not `http`/`https` after normalisation → dropped, never rendered
* unicode / URL-encoded slugs: **built** with `URLEncoder.encode(s, "UTF-8")` per value, and
  **parsed** tolerantly (a `+` in a query is read as a space, `%xx` decoded)

## 6. Rate limits — the constraint that shaped the app

From `api/conf/settings/rest_framework.py` (code defaults; production is configured from env but
these are the documented baselines):

| Scope | Anonymous | Registered (`standard`) |
|---|---|---|
| burst | **5 / hour** | 100 / minute |
| sustained | **100 / day** | 10 000 / day |
| thumbnails | 150 / minute | (per-key) |

Five requests an hour is unusable for browsing on its own, so the app is built around it:

1. **Aggressive TTL caching** (see §7) so a session costs very few requests.
2. **Honest 429 handling**: the throttled screen names the number and offers the fix.
3. **Optional free API key** in Settings: paste `client_id` + `client_secret` from
   `POST /v1/auth_tokens/register/` (free, no approval needed), the app does the
   client-credentials exchange against `/v1/auth_tokens/token/`, caches the bearer token until
   expiry, and refreshes it automatically. `GET /rate_limit/` then displays your live quota.
   This is the difference between 5/hour and 100/minute.

## 7. Caching policy (per type)

| Data | TTL | Rationale |
|---|---|---|
| `/images/stats/`, `/audio/stats/` (source index) | 24 h | nearly static |
| browse / search feeds | 10 min | cheap re-opens, fresh enough |
| detail + related | 30 min | stable per id |
| token | until `expires_in` minus 60 s | |

Memory LRU in front of a disk cache (`context.cacheDir/ert_cache`), both keyed by the full
request URL plus the auth state. **Retry always forces a refresh** — a stale cache must never be
able to hide a fix, and a cached error is never served as success.

## 8. Failure modes and how the app reports them

| Condition | Detection | UI |
|---|---|---|
| 404 | HTTP 404 | `Not found (404) · /images/{id}` + Retry |
| 400 | HTTP 400 + DRF body | shows the offending param from the body |
| 429 | HTTP 429 (+`Retry-After`) | "Rate limited" + wait hint + API-key CTA |
| timeout | 10 s connect / 25 s read | `Timed out · <url>` + Retry; job is cancellable |
| no network | `UnknownHostException` etc. | `Offline · <url>` + Retry |
| dead CDN file | Coil error / ExoPlayer error | thumbnail→`url` fallback, then "Open original page" |
| empty result set | `result_count == 0` | "No results" + the exact URL that produced it |
| unstable param removed | unknown field → 400 | surfaced verbatim in Diagnostics |

Every error string carries the **exact failing URL**, per the brief.

## 9. Fixtures

`fixtures/` holds one file per page type, all schema-accurate (see §0):

* `images_search.json` — search/browse envelope, 3 image results, unicode + URL-encoded tag slugs
* `images_detail.json` — single image detail (no envelope)
* `images_related.json` — related envelope (note the hard-coded `result_count: 10`)
* `images_stats.json` — source/provider index for the A–Z screen
* `audio_search.json` — audio envelope incl. a result with `thumbnail: null`
* `audio_detail.json` — audio detail with `alt_files`, `duration`, `peaks`, `waveform`
* `error_404.json`, `error_429.json`, `error_400.json` — DRF error bodies
* `triple_slash.json` — a result whose `url` / `thumbnail` are malformed, to test normalisation
* `empty_search.json` — `result_count: 0`

## 10. Screen → endpoint map

| Screen | Request |
|---|---|
| Images (browse) | `GET /v1/images/?page=N&page_size=20&filter_dead=true&unstable__sort_by=indexed_on&unstable__sort_dir=desc` + active chips |
| Audio (browse) | `GET /v1/audio/?...` same shape |
| Search | `GET /v1/images/?q=<encoded>&...` (or `/audio/` when the Audio tab is active) |
| Sources (A–Z) | `GET /v1/images/stats/` (and `/v1/audio/stats/`) → tap filters by `source=` |
| Detail | `GET /v1/images/{id}/` + `GET /v1/images/{id}/related/` |
| Tag / creator chips | `tags=` / `creator=` on the relevant media type |
| Saved | local only (SharedPreferences JSON) |
| Diagnostics | any of the above, chosen from a preset list or typed by hand |
