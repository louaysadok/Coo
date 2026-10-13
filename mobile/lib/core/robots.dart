import 'fetcher.dart';

/// Parsed robots.txt rules for this app's user agent.
class Robots {
  Robots._(this._rules, this.crawlDelay);

  static const agentToken = 'politescraper';

  final List<_Rule> _rules;
  final double? crawlDelay;

  /// No robots.txt (4xx): everything is allowed.
  factory Robots.allowAll() => Robots._(const [], null);

  /// Server error or unreachable: be conservative and allow nothing.
  factory Robots.disallowAll() => Robots._([_Rule(false, '/')], null);

  factory Robots.parse(String body) {
    final groups = <_Group>[];
    _Group? current;
    var lastWasAgent = false;
    for (var line in body.split(RegExp(r'\r?\n'))) {
      final hash = line.indexOf('#');
      if (hash >= 0) line = line.substring(0, hash);
      line = line.trim();
      if (line.isEmpty) continue;
      final colon = line.indexOf(':');
      if (colon < 0) continue;
      final key = line.substring(0, colon).trim().toLowerCase();
      final value = line.substring(colon + 1).trim();

      if (key == 'user-agent') {
        if (current == null || !lastWasAgent) {
          current = _Group();
          groups.add(current);
        }
        current.agents.add(value.toLowerCase());
        lastWasAgent = true;
        continue;
      }
      lastWasAgent = false;
      if (current == null) continue;
      switch (key) {
        case 'allow':
          current.rules.add(_Rule(true, value));
        case 'disallow':
          if (value.isNotEmpty) current.rules.add(_Rule(false, value));
        case 'crawl-delay':
          current.delay = double.tryParse(value);
      }
    }

    _Group? chosen;
    for (final g in groups) {
      if (g.agents.contains(agentToken)) {
        chosen = g;
        break;
      }
    }
    chosen ??= groups.where((g) => g.agents.contains('*')).firstOrNull;
    if (chosen == null) return Robots.allowAll();
    return Robots._(chosen.rules, chosen.delay);
  }

  /// Longest matching rule wins; Allow beats Disallow on ties.
  bool allows(Uri uri) {
    var path = uri.path.isEmpty ? '/' : uri.path;
    if (uri.hasQuery) path = '$path?${uri.query}';
    _Rule? best;
    for (final r in _rules) {
      if (!r.matches(path)) continue;
      if (best == null ||
          r.pattern.length > best.pattern.length ||
          (r.pattern.length == best.pattern.length && r.allow)) {
        best = r;
      }
    }
    return best == null || best.allow;
  }
}

class _Group {
  final agents = <String>[];
  final rules = <_Rule>[];
  double? delay;
}

class _Rule {
  _Rule(this.allow, this.pattern) : _re = _compile(pattern);

  final bool allow;
  final String pattern;
  final RegExp _re;

  bool matches(String path) => _re.hasMatch(path);

  static RegExp _compile(String pattern) {
    var p = pattern;
    final anchored = p.endsWith(r'$');
    if (anchored) p = p.substring(0, p.length - 1);
    final buf = StringBuffer('^');
    for (final ch in p.split('')) {
      buf.write(ch == '*' ? '.*' : RegExp.escape(ch));
    }
    if (anchored) buf.write(r'$');
    return RegExp(buf.toString());
  }
}

/// Caches one robots.txt per origin for the whole session.
class RobotsCache {
  RobotsCache(this.fetcher);

  final Fetcher fetcher;
  final _cache = <String, Future<Robots>>{};

  Future<Robots> forUrl(Uri uri) {
    final origin = '${uri.scheme}://${uri.authority}';
    return _cache.putIfAbsent(origin, () => _load(origin));
  }

  Future<Robots> _load(String origin) async {
    final res = await fetcher.get('$origin/robots.txt', forceRead: true);
    final s = res.status;
    if (s != null && s >= 200 && s < 300 && res.error == null) {
      return Robots.parse(res.text);
    }
    if (s != null && s >= 400 && s < 500) return Robots.allowAll();
    return Robots.disallowAll();
  }
}
