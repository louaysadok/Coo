import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:http/http.dart' as http;

import 'net_safety.dart';

const _redirectCodes = {301, 302, 303, 307, 308};
const _maxRedirects = 5;

class FetchResult {
  const FetchResult({
    required this.url,
    required this.finalUrl,
    required this.status,
    required this.contentType,
    required this.text,
    required this.error,
  });

  final String url;
  final String finalUrl;
  final int? status;
  final String contentType;
  final String text;
  final String? error;

  bool get isHtml => contentType.contains('html');
}

/// Polite HTTP client: per-host throttling, size cap, and a public-address
/// check on every hop, including redirects.
class Fetcher {
  Fetcher({
    required this.userAgent,
    http.Client? client,
    this.floor = const Duration(milliseconds: 250),
    this.maxBytes = 3000000,
    this.timeout = const Duration(seconds: 20),
    Future<bool> Function(Uri uri)? publicCheck,
  })  : _client = client ?? http.Client(),
        publicCheck = publicCheck ?? isPublicUrl;

  final String userAgent;
  final Duration floor;
  final int maxBytes;
  final Duration timeout;
  final Future<bool> Function(Uri uri) publicCheck;
  final http.Client _client;
  final _lastRequest = <String, DateTime>{};

  Future<void> _throttle(String host, Duration delay) async {
    final last = _lastRequest[host];
    if (last != null) {
      final wait = last.add(delay).difference(DateTime.now());
      if (wait > Duration.zero) await Future<void>.delayed(wait);
    }
    _lastRequest[host] = DateTime.now();
  }

  Future<FetchResult> get(
    String url, {
    Duration minDelay = Duration.zero,
    bool forceRead = false,
  }) async {
    var uri = Uri.parse(url);
    await _throttle(uri.host, minDelay > floor ? minDelay : floor);
    try {
      for (var hop = 0; hop <= _maxRedirects; hop++) {
        if (!await publicCheck(uri)) {
          return _fail(url, 'Blocked: address is not public');
        }
        final req = http.Request('GET', uri)
          ..followRedirects = false
          ..headers.addAll({
            'User-Agent': userAgent,
            'Accept': 'text/html,application/xhtml+xml;q=0.9,*/*;q=0.5',
          });
        final resp = await _client.send(req).timeout(timeout);
        final status = resp.statusCode;

        final location = resp.headers['location'];
        if (_redirectCodes.contains(status) && location != null) {
          await resp.stream.drain<void>();
          uri = uri.resolveUri(Uri.parse(location));
          continue;
        }

        final ctype = (resp.headers['content-type'] ?? '')
            .split(';')
            .first
            .trim()
            .toLowerCase();
        final textual = forceRead ||
            ctype.startsWith('text/') ||
            ctype.contains('html') ||
            ctype.contains('xml') ||
            ctype.contains('json');
        var text = '';
        if (textual) {
          final builder = BytesBuilder(copy: false);
          await for (final chunk in resp.stream) {
            if (builder.length + chunk.length > maxBytes) break;
            builder.add(chunk);
          }
          text = utf8.decode(builder.takeBytes(), allowMalformed: true);
        } else {
          await resp.stream.drain<void>();
        }
        return FetchResult(
          url: url,
          finalUrl: uri.toString(),
          status: status,
          contentType: ctype,
          text: text,
          error: status >= 400 ? 'HTTP $status' : null,
        );
      }
      return _fail(url, 'Too many redirects');
    } on TimeoutException {
      return _fail(url, 'Timed out');
    } catch (e) {
      return _fail(url, '${e.runtimeType}: $e');
    }
  }

  FetchResult _fail(String url, String message) => FetchResult(
        url: url,
        finalUrl: url,
        status: null,
        contentType: '',
        text: '',
        error: message,
      );

  void close() => _client.close();
}
