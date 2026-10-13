"""Safety checks: only fetch URLs that resolve to public internet addresses.

This stops the scraper from being pointed at localhost, the LAN, or cloud
metadata endpoints (a common server-side request forgery risk).
"""
from __future__ import annotations

import ipaddress
import socket
from urllib.parse import urlsplit


def is_public_url(url: str) -> bool:
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        return False
    port = parts.port or (443 if parts.scheme == "https" else 80)
    try:
        infos = socket.getaddrinfo(parts.hostname, port, proto=socket.IPPROTO_TCP)
    except (socket.gaierror, UnicodeError, ValueError):
        return False
    if not infos:
        return False
    for info in infos:
        raw = info[4][0].split("%", 1)[0]
        try:
            ip = ipaddress.ip_address(raw)
        except ValueError:
            return False
        if not ip.is_global:
            return False
    return True
