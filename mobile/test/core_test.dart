import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:polite_scraper/core/crawler.dart';
import 'package:polite_scraper/core/net_safety.dart';
import 'package:polite_scraper/core/parser.dart';
import 'package:polite_scraper/core/robots.dart';
import 'package:polite_scraper/core/url.dart';

void main() {
  group('normalizeUrl', () {
    test('lowercases host and drops default port and fragment', () {
      expect(normalizeUrl('HTTPS://Example.COM:443/a#frag'),
          'https://example.com/a');
    });

    test('keeps non-default port and query', () {
      expect(normalizeUrl('http://example.com:8080/x?b=2'),
          'http://example.com:8080/x?b=2');
    });

    test('empty path becomes slash', () {
      expect(normalizeUrl('https://example.com'), 'https://example.com/');
    });

    test('resolves relative links', () {
      expect(normalizeUrl('../b', 'https://example.com/a/c/d'),
          'https://example.com/a/b');
    });

    test('rejects non-http schemes and empty input', () {
      expect(normalizeUrl('mailto:me@example.com'), isNull);
      expect(normalizeUrl('javascript:alert(1)'), isNull);
      expect(normalizeUrl('ftp://example.com/'), isNull);
      expect(normalizeUrl('   '), isNull);
    });
  });

  group('isPublicAddress', () {
    test('rejects private and loopback IPv4', () {
      for (final ip in ['127.0.0.1', '10.1.2.3', '192.168.1.1', '172.16.0.5',
          '169.254.169.254', '100.64.0.1', '0.0.0.0']) {
        expect(isPublicAddress(InternetAddress(ip)), isFalse, reason: ip);
      }
    });

    test('accepts ordinary public IPv4', () {
      for (final ip in ['8.8.8.8', '1.1.1.1', '93.184.216.34']) {
        expect(isPublicAddress(InternetAddress(ip)), isTrue, reason: ip);
      }
    });

    test('rejects loopback and unique-local IPv6', () {
      expect(isPublicAddress(InternetAddress('::1')), isFalse);
      expect(isPublicAddress(InternetAddress('fd00::1')), isFalse);
      expect(isPublicAddress(InternetAddress('fe80::1')), isFalse);
    });
  });

  group('Robots', () {
    test('disallow applies to our agent group', () {
      final r = Robots.parse('''
User-agent: *
Disallow: /private

User-agent: PoliteScraper
Disallow: /secret
''');
      expect(r.allows(Uri.parse('https://x.test/secret/a')), isFalse);
      // Our specific group wins over '*', so /private is allowed for us.
      expect(r.allows(Uri.parse('https://x.test/private/a')), isTrue);
    });

    test('wildcard group applies when no specific group exists', () {
      final r = Robots.parse('User-agent: *\nDisallow: /private\n');
      expect(r.allows(Uri.parse('https://x.test/private/a')), isFalse);
      expect(r.allows(Uri.parse('https://x.test/public')), isTrue);
    });

    test('longest match and Allow beat Disallow', () {
      final r = Robots.parse('''
User-agent: *
Disallow: /docs
Allow: /docs/public
''');
      expect(r.allows(Uri.parse('https://x.test/docs/public/a')), isTrue);
      expect(r.allows(Uri.parse('https://x.test/docs/other')), isFalse);
    });

    test('wildcard and end anchor patterns', () {
      final r = Robots.parse('User-agent: *\nDisallow: /*.pdf\$\n');
      expect(r.allows(Uri.parse('https://x.test/file.pdf')), isFalse);
      expect(r.allows(Uri.parse('https://x.test/file.pdf?x=1')), isTrue);
    });

    test('crawl-delay is read for our agent', () {
      final r = Robots.parse('User-agent: *\nCrawl-delay: 5\n');
      expect(r.crawlDelay, 5.0);
    });

    test('allowAll and disallowAll', () {
      expect(Robots.allowAll().allows(Uri.parse('https://x.test/a')), isTrue);
      expect(Robots.disallowAll().allows(Uri.parse('https://x.test/a')), isFalse);
    });
  });

  group('parseHtml', () {
    const html = '''
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
''';

    test('extracts title, description and readable text', () {
      final out = parseHtml(html, 'https://example.com/');
      expect(out.title, 'Hello World');
      expect(out.description, 'A test page');
      expect(out.text, contains('Some text here.'));
      expect(out.text, isNot(contains('var x')));
      expect(out.text, isNot(contains('color:red')));
    });

    test('links are absolute and unique', () {
      final out = parseHtml(html, 'https://example.com/');
      expect(out.links, [
        'https://example.com/about',
        'https://other.example/x',
      ]);
    });
  });

  group('validateJobSettings', () {
    test('accepts good input and normalises the URL', () {
      final s = validateJobSettings(
          url: 'HTTPS://X.test', maxPages: 10, maxDepth: 1, delay: 1, sameSite: true);
      expect(s.startUrl, 'https://x.test/');
    });

    test('rejects bad input with readable messages', () {
      expect(
          () => validateJobSettings(
              url: 'ftp://x', maxPages: 10, maxDepth: 1, delay: 1, sameSite: true),
          throwsFormatException);
      expect(
          () => validateJobSettings(
              url: 'https://x.test', maxPages: 0, maxDepth: 1, delay: 1, sameSite: true),
          throwsFormatException);
      expect(
          () => validateJobSettings(
              url: 'https://x.test', maxPages: 10, maxDepth: 1, delay: 0.01, sameSite: true),
          throwsFormatException);
    });
  });
}
