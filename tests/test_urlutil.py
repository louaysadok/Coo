from scraper.urlutil import normalize


def test_normalizes_case_port_and_fragment():
    assert normalize("HTTPS://Example.COM:443/a#frag") == "https://example.com/a"


def test_keeps_non_default_port_and_query():
    assert normalize("http://example.com:8080/x?b=2") == "http://example.com:8080/x?b=2"


def test_empty_path_becomes_slash():
    assert normalize("https://example.com") == "https://example.com/"


def test_resolves_relative_links():
    assert normalize("../b", "https://example.com/a/c/d") == "https://example.com/a/b"


def test_rejects_non_http_schemes():
    assert normalize("mailto:me@example.com") is None
    assert normalize("javascript:alert(1)") is None
    assert normalize("ftp://example.com/") is None
