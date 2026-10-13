# Polite Scraper

A general-purpose web scraper with a clean web UI. Give it a public website
address and it crawls the pages (up to limits you set), then stores titles,
descriptions and text so you can search them, favourite them, and export or
back them up.

No API keys or logins are used. It only reads pages a site serves publicly.

## Features

- **Respects the site:** reads `robots.txt` (including `Disallow` and `Crawl-delay`),
  waits between requests per host (minimum 0.25 s), and caps pages and depth.
- **Safe:** only fetches public addresses. Localhost, LAN and cloud-metadata
  addresses are blocked, including via redirects.
- **Controllable:** start, stop, resume, and delete jobs. Progress updates live.
- **Searchable results:** search titles, descriptions, text and URLs in each job.
- **Favourites:** a dropdown switches between favourite *pages* and favourite *jobs*.
- **Export:** CSV per job, and a full backup download (SQLite DB + JSON).
- **Backups:** `python -m scraper.backup` writes timestamped zips and keeps the newest N.

## Quick start

```bash
python3 -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements.txt
SCRAPER_USER_AGENT="PoliteScraper/1.0 (+you@example.com)" \
  uvicorn app:app --host 0.0.0.0 --port 8000
```

Open http://localhost:8000.

Set `SCRAPER_USER_AGENT` to a contact your target sites can reach. The data
lives in `data/scraper.db` by default (override with `SCRAPER_DATA_DIR`).

## Backups

- **In the app:** click **Backup** in the top bar to download a zip.
- **From the command line:** `python -m scraper.backup --dir backups --keep 30`.
  Schedule it with cron or Task Scheduler.
- **Restore:** stop the app, then copy `scraper.db` from the zip to `data/scraper.db`.

## Tests

```bash
pytest -q
```

## Layout

```
app.py              FastAPI routes
scraper/
  crawler.py        breadth-first crawl loop, validation
  fetch.py          HTTP client, per-host throttle, size limit, SSRF guard
  robots.py         robots.txt rules and crawl-delay
  parse.py          title/description/text/link extraction
  safety.py         public-address check
  store.py          SQLite schema and queries
  backup.py         zip backups and pruning
templates/, static/ UI
tests/              unit and crawler tests (mocked HTTP, no network)
```

## Limits and honest notes

- Crawls are single-threaded per job so they stay gentle on servers.
- JavaScript-rendered pages are read as their initial HTML only; no browser is run.
- Each page keeps up to 20,000 characters of text, and responses over 3 MB are cut off.
- There is no login. The app has no authentication, so run it on a trusted machine or behind your own access control if you expose it.
