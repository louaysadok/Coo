"""HTML parsing: title, description, readable text and outgoing links."""
from __future__ import annotations

import re

from bs4 import BeautifulSoup

from .urlutil import normalize

MAX_TEXT = 20_000
MAX_TITLE = 300
MAX_DESCRIPTION = 1_000
_SKIP_TAGS = ["script", "style", "noscript", "template", "svg", "iframe"]


def parse_html(html: str, base_url: str) -> dict:
    soup = BeautifulSoup(html, "html.parser")

    title = ""
    if soup.title and soup.title.string:
        title = soup.title.string.strip()

    description = ""
    meta = soup.find("meta", attrs={"name": re.compile(r"^description$", re.I)})
    if meta is None:
        meta = soup.find("meta", attrs={"property": "og:description"})
    if meta is not None and meta.get("content"):
        description = str(meta["content"]).strip()

    links: list[str] = []
    seen: set[str] = set()
    for a in soup.find_all("a", href=True):
        target = normalize(str(a["href"]), base_url)
        if target and target not in seen:
            seen.add(target)
            links.append(target)

    for tag in soup(_SKIP_TAGS):
        tag.decompose()
    text = " ".join(soup.get_text(" ").split())

    return {
        "title": title[:MAX_TITLE],
        "description": description[:MAX_DESCRIPTION],
        "text": text[:MAX_TEXT],
        "links": links,
    }
