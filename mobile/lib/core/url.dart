/// Canonical http(s) URL, or null if the input cannot be used.
///
/// Lowercases scheme and host, drops default ports and fragments,
/// and resolves [input] against [base] when given.
String? normalizeUrl(String input, [String? base]) {
  final raw = input.trim();
  if (raw.isEmpty) return null;
  Uri uri;
  try {
    uri = base == null
        ? Uri.parse(raw)
        : Uri.parse(base).resolveUri(Uri.parse(raw));
  } on FormatException {
    return null;
  }
  final scheme = uri.scheme.toLowerCase();
  if (scheme != 'http' && scheme != 'https') return null;
  if (uri.host.isEmpty) return null;
  final defaultPort = scheme == 'https' ? 443 : 80;
  final port = uri.hasPort && uri.port != defaultPort ? uri.port : null;
  final path = uri.path.isEmpty ? '/' : uri.path;
  return Uri(
    scheme: scheme,
    host: uri.host.toLowerCase(),
    port: port,
    path: path,
    query: uri.hasQuery ? uri.query : null,
  ).toString();
}
