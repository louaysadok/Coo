"""Web UI for the polite scraper. Run with:  uvicorn app:app --host 0.0.0.0 --port 8000"""
from __future__ import annotations

import csv
import io
import os
import threading
import time
from pathlib import Path

from fastapi import FastAPI, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from scraper.backup import make_backup_zip
from scraper.crawler import crawl, validate_settings
from scraper.fetch import Fetcher
from scraper.robots import RobotsCache
from scraper.store import Store

BASE = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("SCRAPER_DATA_DIR", BASE / "data"))
USER_AGENT = os.environ.get(
    "SCRAPER_USER_AGENT",
    "PoliteScraper/1.0 (+https://github.com/louaysadok/Coo; contact: set SCRAPER_USER_AGENT)",
)
PAGE_SIZE = 50

store = Store(DATA_DIR / "scraper.db")
store.mark_interrupted()
fetcher = Fetcher(USER_AGENT)
robots = RobotsCache(fetcher)

app = FastAPI(title="Polite Scraper")
app.mount("/static", StaticFiles(directory=BASE / "static"), name="static")
templates = Jinja2Templates(directory=BASE / "templates")


def _fmt_time(ts) -> str:
    if not ts:
        return "—"
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(float(ts)))


templates.env.filters["ftime"] = _fmt_time


def _render(request: Request, name: str, status: int = 200, **ctx) -> HTMLResponse:
    return templates.TemplateResponse(request, name, ctx, status_code=status)


def _start_job(job_id: int) -> None:
    threading.Thread(target=crawl, args=(store, job_id, fetcher, robots),
                     name=f"crawl-{job_id}", daemon=True).start()


@app.get("/", response_class=HTMLResponse)
def dashboard(request: Request):
    return _render(request, "index.html", active="dashboard",
                   jobs=store.list_jobs(), form={}, error=None)


@app.post("/jobs", response_class=HTMLResponse)
def create_job(
    request: Request,
    url: str = Form(""),
    max_pages: int = Form(50),
    max_depth: int = Form(2),
    delay: float = Form(1.0),
    same_site: str | None = Form(None),
):
    form = {"url": url, "max_pages": max_pages, "max_depth": max_depth,
            "delay": delay, "same_site": bool(same_site)}
    try:
        cfg = validate_settings(url, max_pages, max_depth, delay, bool(same_site))
    except ValueError as exc:
        return _render(request, "index.html", 400, active="dashboard",
                       jobs=store.list_jobs(), form=form, error=str(exc))
    job_id = store.create_job(created_at=time.time(), **cfg)
    _start_job(job_id)
    return RedirectResponse(f"/jobs/{job_id}", status_code=303)


def _job_or_404(job_id: int) -> dict:
    job = store.get_job(job_id)
    if job is None:
        raise HTTPException(404, "Job not found")
    return job


@app.get("/jobs/{job_id}", response_class=HTMLResponse)
def job_detail(request: Request, job_id: int, q: str = "", page: int = 1):
    job = _job_or_404(job_id)
    page = max(page, 1)
    rows, total = store.list_pages(job_id, q=q, limit=PAGE_SIZE,
                                   offset=(page - 1) * PAGE_SIZE)
    fav_pages = store.favorite_ids("page")
    for row in rows:
        row["favorite"] = row["id"] in fav_pages
    return _render(request, "job.html", active="dashboard", job=job, pages=rows,
                   total=total, q=q, page=page, page_size=PAGE_SIZE,
                   job_favorite=job_id in store.favorite_ids("job"),
                   running=job["status"] in ("queued", "running"))


@app.post("/jobs/{job_id}/cancel")
def cancel_job(job_id: int):
    _job_or_404(job_id)
    store.request_cancel(job_id)
    return RedirectResponse(f"/jobs/{job_id}", status_code=303)


@app.post("/jobs/{job_id}/resume")
def resume_job(job_id: int):
    """Re-crawl from the start URL. Already-stored pages are kept (no duplicates)."""
    job = _job_or_404(job_id)
    if job["status"] in ("queued", "running"):
        return RedirectResponse(f"/jobs/{job_id}", status_code=303)
    store.update_job(job_id, status="queued", cancel_requested=0, error=None)
    _start_job(job_id)
    return RedirectResponse(f"/jobs/{job_id}", status_code=303)


@app.post("/jobs/{job_id}/delete")
def delete_job(job_id: int):
    _job_or_404(job_id)
    store.delete_job(job_id)
    return RedirectResponse("/", status_code=303)


@app.get("/jobs/{job_id}/export.csv")
def export_job_csv(job_id: int):
    _job_or_404(job_id)
    buf = io.StringIO()
    writer = csv.writer(buf)
    writer.writerow(["url", "final_url", "depth", "status_code", "title",
                     "description", "error", "fetched_at"])
    for p in store.all_pages(job_id):
        writer.writerow([p["url"], p["final_url"], p["depth"], p["status_code"],
                         p["title"], p["description"], p["error"],
                         _fmt_time(p["fetched_at"])])
    return Response(buf.getvalue(), media_type="text/csv",
                    headers={"Content-Disposition": f'attachment; filename="job-{job_id}.csv"'})


@app.get("/pages/{page_id}", response_class=HTMLResponse)
def page_detail(request: Request, page_id: int):
    page = store.get_page(page_id)
    if page is None:
        raise HTTPException(404, "Page not found")
    return _render(request, "page.html", active="dashboard", page=page,
                   favorite=page_id in store.favorite_ids("page"))


@app.post("/favorites/toggle")
def toggle_favorite(kind: str = Form(...), ref_id: int = Form(...),
                    back: str = Form("/")):
    if kind not in ("page", "job"):
        raise HTTPException(400, "Bad kind")
    store.toggle_favorite(kind, ref_id, time.time())
    if not back.startswith("/") or back.startswith("//"):
        back = "/"
    return RedirectResponse(back, status_code=303)


@app.get("/favorites", response_class=HTMLResponse)
def favorites(request: Request, kind: str = "page"):
    if kind not in ("page", "job"):
        kind = "page"
    return _render(request, "favorites.html", active="favorites", kind=kind,
                   items=store.list_favorites(kind))


@app.get("/backup")
def download_backup():
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        out = make_backup_zip(store, tmp, keep=100)
        data = out.read_bytes()
    name = out.name
    return Response(data, media_type="application/zip",
                    headers={"Content-Disposition": f'attachment; filename="{name}"'})
