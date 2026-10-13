"""Polite HTTP fetching: per-host throttling, size limits, SSRF guard."""
from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from typing import Callable
from urllib.parse import urlsplit

import httpx

from .safety import is_public_url

MIN_DELAY = 0.25          # never hit one host faster than this (seconds)
DEFAULT_TIMEOUT = 15.0
DEFAULT_MAX_BYTES = 3_000_000
TEXTLIKE = ("text/", "html", "xml", "json")


class BlockedAddress(httpx.TransportError):
    """Raised when a request would go to a non-public address."""


@dataclass
class FetchResult:
    url: str
    final_url: str
    status: int | None
    content_type: str
    text: str
    error: str | None

    @property
    def is_html(self) -> bool:
        return "html" in self.content_type


class Fetcher:
    def __init__(
        self,
        user_agent: str,
        *,
        timeout: float = DEFAULT_TIMEOUT,
        max_bytes: int = DEFAULT_MAX_BYTES,
        floor: float = MIN_DELAY,
        transport: httpx.BaseTransport | None = None,
        public_check: Callable[[str], bool] = is_public_url,
    ) -> None:
        self.max_bytes = max_bytes
        self.floor = floor
        self.public_check = public_check
        self._last: dict[str, float] = {}
        self._lock = threading.Lock()

        def _guard(request: httpx.Request) -> None:
            if not self.public_check(str(request.url)):
                raise BlockedAddress("address is not public")

        self.client = httpx.Client(
            headers={
                "User-Agent": user_agent,
                "Accept": "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5",
            },
            timeout=timeout,
            follow_redirects=True,
            max_redirects=5,
            transport=transport,
            event_hooks={"request": [_guard]},
        )

    def _throttle(self, host: str, delay: float) -> None:
        with self._lock:
            last = self._last.get(host)
            if last is not None:
                remaining = last + delay - time.monotonic()
                if remaining > 0:
                    time.sleep(remaining)
            self._last[host] = time.monotonic()

    def get(self, url: str, min_delay: float = 0.0, force_read: bool = False) -> FetchResult:
        host = urlsplit(url).hostname or ""
        self._throttle(host, max(min_delay, self.floor))
        try:
            with self.client.stream("GET", url) as resp:
                ctype = resp.headers.get("content-type", "").split(";")[0].strip().lower()
                final = str(resp.url)
                text = ""
                if force_read or any(t in ctype for t in TEXTLIKE):
                    chunks, size = [], 0
                    for chunk in resp.iter_bytes():
                        size += len(chunk)
                        if size > self.max_bytes:
                            break
                        chunks.append(chunk)
                    raw = b"".join(chunks)
                    encoding = resp.encoding or "utf-8"
                    text = raw.decode(encoding, errors="replace")
                error = f"HTTP {resp.status_code}" if resp.status_code >= 400 else None
                return FetchResult(url, final, resp.status_code, ctype, text, error)
        except BlockedAddress as exc:
            return FetchResult(url, url, None, "", "", f"Blocked: {exc}")
        except httpx.HTTPError as exc:
            return FetchResult(url, url, None, "", "", f"{type(exc).__name__}: {exc}")
