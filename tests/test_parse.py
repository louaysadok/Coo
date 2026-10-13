from scraper.parse import parse_html

HTML = """
<html><head><title>  Hello World </title>
<meta name="description" content="A test page">
<style>body{color:red}</style></head>
<body><script>var x=1;</script>
<h1>Main</h1><p>Some   text here.</p>
<a href="/about">About</a>
<a href="https://other.example/x">Other</a>
<a href="/about">Dup</a>
<a href="mailto:a@b.c">mail</a>
</body></html>
"""


def test_extracts_title_description_text():
    out = parse_html(HTML, "https://example.com/")
    assert out["title"] == "Hello World"
    assert out["description"] == "A test page"
    assert "Some text here." in out["text"]
    assert "var x" not in out["text"]
    assert "color:red" not in out["text"]


def test_links_are_absolute_and_unique():
    out = parse_html(HTML, "https://example.com/")
    assert out["links"] == ["https://example.com/about", "https://other.example/x"]
