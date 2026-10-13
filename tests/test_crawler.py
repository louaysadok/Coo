import httpx
import pytest

from scraper.backup import make_backup_zip, prune_backups
from scraper.crawler import crawl, validate_settings
from scraper.fetch import Fetcher
from scraper.robots import RobotsCache
from scraper.store import Store

SITE = {
    "https://site.test/": """<html><head><title>Home</title></head><body>
        <p>welcome</p><a href="/a">A</a><a href="/b">B</a>
        <a href="https://offsite.test/x">off</a></body></html>""",
    "https://site.test/a": "<html><head><title>Page A</title></head><body>alpha <a href='/deep'>d</a></body></html>",
    "https://site.test/b": "<html><head><title>Page B</title></head><body>beta</body></html>",
    "https://site.test/deep": "<html><head><title>Deep</title></head><body>deep text</body></html>",
    "https://offsite.test/x": "<html><head><title>Off</title></head><body>should not be fetched</body></html>",
}
FETCHED: list[str] = []


def handler(request: httpx.Request) -> httpx.Response:
    url = str(request.url)
    if url.endswith("/robots.txt"):
        return httpx.Response(404)
    FETCHED.append(url)
    if url in SITE:
        return httpx.Response(200, text=SITE[url], headers={"content-type": "text/html; charset=utf-8"})
    return httpx.Response(404, text="nope", headers={"content-type": "text/html"})


@pytest.fixture
def env(tmp_path):
    FETCHED.clear()
    store = Store(tmp_path / "t.db")
    fetcher = Fetcher("TestBot/1.0", floor=0.0, transport=httpx.MockTransport(handler),
                      public_check=lambda u: True)
    robots = RobotsCache(fetcher)
    yield store, fetcher, robots
    store.close()


def _job(store, **over):
    cfg = dict(start_url="https://site.test/", max_pages=50, max_depth=2,
               delay=0.25, same_site=True, created_at=0.0)
    cfg.update(over)
    return store.create_job(**cfg)


def test_crawl_follows_same_site_links_within_depth(env):
    store, fetcher, robots = env
    job_id = _job(store)
    crawl(store, job_id, fetcher, robots, clock=lambda: 1.0)
    job = store.get_job(job_id)
    assert job["status"] == "completed"
    pages, total = store.list_pages(job_id)
    urls = {p["url"] for p in pages}
    assert "https://site.test/deep" in urls
    assert "https://offsite.test/x" not in urls
    assert not any("offsite.test" in u for u in FETCHED)


def test_depth_limit_stops_expansion(env):
    store, fetcher, robots = env
    job_id = _job(store, max_depth=1)
    crawl(store, job_id, fetcher, robots, clock=lambda: 1.0)
    urls = {p["url"] for p in store.all_pages(job_id)}
    assert "https://site.test/deep" not in urls
    assert "https://site.test/a" in urls


def test_max_pages_is_respected(env):
    store, fetcher, robots = env
    job_id = _job(store, max_pages=2)
    crawl(store, job_id, fetcher, robots, clock=lambda: 1.0)
    assert store.get_job(job_id)["pages_fetched"] == 2


def test_cancel_stops_crawl(env):
    store, fetcher, robots = env
    job_id = _job(store)
    store.request_cancel(job_id)
    crawl(store, job_id, fetcher, robots, clock=lambda: 1.0)
    assert store.get_job(job_id)["status"] == "cancelled"
    assert store.list_pages(job_id)[1] == 0


def test_robots_disallow_is_respected(tmp_path):
    def handler_r(request):
        if request.url.path == "/robots.txt":
            return httpx.Response(200, text="User-agent: *\nDisallow: /private\n")
        return httpx.Response(200, text="<html><title>x</title><a href='/private/x'>p</a></html>",
                              headers={"content-type": "text/html"})

    store = Store(tmp_path / "r.db")
    fetcher = Fetcher("TestBot/1.0", floor=0.0, transport=httpx.MockTransport(handler_r),
                      public_check=lambda u: True)
    job_id = _job(store, start_url="https://site.test/")
    crawl(store, job_id, fetcher, RobotsCache(fetcher), clock=lambda: 1.0)
    blocked = [p for p in store.all_pages(job_id) if p["url"].endswith("/private/x")]
    assert blocked and blocked[0]["error"] == "Blocked by robots.txt"
    store.close()


def test_non_public_address_is_blocked(tmp_path):
    store = Store(tmp_path / "p.db")
    fetcher = Fetcher("TestBot/1.0", floor=0.0, transport=httpx.MockTransport(handler),
                      public_check=lambda u: False)
    job_id = _job(store)
    crawl(store, job_id, fetcher, RobotsCache(fetcher), clock=lambda: 1.0)
    pages = store.all_pages(job_id)
    assert pages and all("not public" in (p["error"] or "") for p in pages)
    store.close()


def test_validate_settings_rejects_bad_input():
    with pytest.raises(ValueError):
        validate_settings("ftp://x", 10, 1, 1.0, True)
    with pytest.raises(ValueError):
        validate_settings("https://x.test", 0, 1, 1.0, True)
    with pytest.raises(ValueError):
        validate_settings("https://x.test", 10, 1, 0.01, True)
    ok = validate_settings("HTTPS://X.test", 10, 1, 1.0, True)
    assert ok["start_url"] == "https://x.test/"


def test_favorites_toggle_and_list(env):
    store, _, _ = env
    job_id = _job(store)
    assert store.toggle_favorite("job", job_id, 1.0) is True
    assert [j["id"] for j in store.list_favorites("job")] == [job_id]
    assert store.toggle_favorite("job", job_id, 2.0) is False
    assert store.list_favorites("job") == []


def test_backup_zip_and_prune(env, tmp_path):
    store, fetcher, robots = env
    job_id = _job(store)
    crawl(store, job_id, fetcher, robots, clock=lambda: 1.0)
    out = make_backup_zip(store, tmp_path / "bk", keep=5)
    assert out.exists()
    import zipfile
    names = zipfile.ZipFile(out).namelist()
    assert set(names) == {"scraper.db", "export.json"}
    for i in range(6):
        (tmp_path / "bk" / f"scraper-backup-2000010{i}-000000.zip").write_bytes(b"x")
    prune_backups(tmp_path / "bk", keep=3)
    assert len(list((tmp_path / "bk").glob("scraper-backup-*.zip"))) == 3
