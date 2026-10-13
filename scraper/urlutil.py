"""URL helpers: normalisation and basic validation."""
from __future__ import annotations

from urllib.parse import urldefrag, urljoin, urlsplit, urlunsplit

DEFAULT_PORTS = {"http": 80, "https": 443}


def normalize(url: str, base: str | None = None) -> str | None:
    """Return a canonical http(s) URL, or None if it cannot be used.

    Lowercases scheme and host, drops default ports and fragments, and
    fills an empty path with "/".
    """
    if not url:
        return None
    url = url.strip()
    if base:
        url = urljoin(base, url)
    url, _ = urldefrag(url)
    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        return None
    scheme = parts.scheme.lower()
    if scheme not in DEFAULT_PORTS or not parts.hostname:
        return None
    host = parts.hostname.lower()
    if port is None or port == DEFAULT_PORTS[scheme]:
        netloc = host
    else:
        netloc = f"{host}:{port}"
    path = parts.path or "/"
    return urlunsplit((scheme, netloc, path, parts.query, ""))


def host_of(url: str) -> str:
    return (urlsplit(url).hostname or "").lower()
