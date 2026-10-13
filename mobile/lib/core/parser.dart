import 'package:html/parser.dart' as html_parser;

import 'url.dart';

const _maxText = 20000;
const _maxTitle = 300;
const _maxDescription = 1000;

class ParsedPage {
  const ParsedPage({
    required this.title,
    required this.description,
    required this.text,
    required this.links,
  });

  final String title;
  final String description;
  final String text;
  final List<String> links;
}

ParsedPage parseHtml(String body, String baseUrl) {
  final doc = html_parser.parse(body);

  final title = (doc.querySelector('title')?.text ?? '').trim();

  var description = doc
          .querySelector('meta[name="description"]')
          ?.attributes['content'] ??
      '';
  if (description.trim().isEmpty) {
    description = doc
            .querySelector('meta[property="og:description"]')
            ?.attributes['content'] ??
        '';
  }

  final links = <String>[];
  final seen = <String>{};
  for (final a in doc.querySelectorAll('a[href]')) {
    final target = normalizeUrl(a.attributes['href'] ?? '', baseUrl);
    if (target != null && seen.add(target)) links.add(target);
  }

  for (final el in doc.querySelectorAll(
      'script, style, noscript, template, svg, iframe')) {
    el.remove();
  }
  final text = (doc.body?.text ?? '')
      .split(RegExp(r'\s+'))
      .where((w) => w.isNotEmpty)
      .join(' ');

  return ParsedPage(
    title: _cut(title, _maxTitle),
    description: _cut(description.trim(), _maxDescription),
    text: _cut(text, _maxText),
    links: links,
  );
}

String _cut(String s, int max) => s.length <= max ? s : s.substring(0, max);
