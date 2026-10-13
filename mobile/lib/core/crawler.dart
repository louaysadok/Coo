import 'dart:collection';
import 'dart:math';

import '../data/database.dart';
import 'fetcher.dart';
import 'parser.dart';
import 'robots.dart';
import 'url.dart';

const maxPagesLimit = 5000;
const maxDepthLimit = 10;
const minDelaySeconds = 0.25;
const maxDelaySeconds = 60.0;

class JobSettings {
  const JobSettings({
    required this.startUrl,
    required this.maxPages,
    required this.maxDepth,
    required this.delay,
    required this.sameSite,
  });

  final String startUrl;
  final int maxPages;
  final int maxDepth;
  final double delay;
  final bool sameSite;
}

/// Cleans user input. Throws [FormatException] with a readable message.
JobSettings validateJobSettings({
  required String url,
  required int maxPages,
  required int maxDepth,
  required double delay,
  required bool sameSite,
}) {
  final start = normalizeUrl(url);
  if (start == null) {
    throw const FormatException('Enter a full http:// or https:// address.');
  }
  if (maxPages < 1 || maxPages > maxPagesLimit) {
    throw const FormatException('Max pages must be between 1 and $maxPagesLimit.');
  }
  if (maxDepth < 0 || maxDepth > maxDepthLimit) {
    throw const FormatException('Max depth must be between 0 and $maxDepthLimit.');
  }
  if (delay < minDelaySeconds || delay > maxDelaySeconds) {
    throw const FormatException(
        'Delay must be between $minDelaySeconds and $maxDelaySeconds seconds.');
  }
  return JobSettings(
    startUrl: start,
    maxPages: maxPages,
    maxDepth: maxDepth,
    delay: delay,
    sameSite: sameSite,
  );
}

double nowSeconds() => DateTime.now().millisecondsSinceEpoch / 1000.0;

class _Item {
  _Item(this.url, this.depth);
  final String url;
  final int depth;
}

/// Breadth-first, polite, cancellable crawl of one job.
class Crawler {
  Crawler({
    required this.db,
    required this.fetcher,
    required this.robots,
    this.onChange,
  });

  final AppDatabase db;
  final Fetcher fetcher;
  final RobotsCache robots;
  final void Function()? onChange;

  Future<void> run(int jobId) async {
    final job = await db.getJob(jobId);
    if (job == null) return;
    final start = job['start_url'] as String;
    final siteHost = Uri.parse(start).host;
    final maxPages = job['max_pages'] as int;
    final maxDepth = job['max_depth'] as int;
    final delay = (job['delay'] as num).toDouble();
    final sameSite = job['same_site'] == 1;

    await db.updateJob(jobId, {
      'status': 'running',
      'started_at': nowSeconds(),
      'error': null,
    });
    onChange?.call();

    final queue = Queue<_Item>()..add(_Item(start, 0));
    final seen = <String>{start};
    var fetched = 0;
    var status = 'completed';
    String? error;

    try {
      while (queue.isNotEmpty) {
        if (await db.cancelRequested(jobId)) {
          status = 'cancelled';
          break;
        }
        if (fetched >= maxPages) break;

        final item = queue.removeFirst();
        final uri = Uri.parse(item.url);

        if (!await fetcher.publicCheck(uri)) {
          await db.addPage(jobId, {
            'url': item.url,
            'depth': item.depth,
            'fetched_at': nowSeconds(),
            'error': 'Blocked: address is not public',
          });
          onChange?.call();
          continue;
        }

        final rules = await robots.forUrl(uri);
        if (!rules.allows(uri)) {
          await db.addPage(jobId, {
            'url': item.url,
            'depth': item.depth,
            'fetched_at': nowSeconds(),
            'error': 'Blocked by robots.txt',
          });
          onChange?.call();
          continue;
        }

        final wait = max(delay, rules.crawlDelay ?? 0);
        final res = await fetcher.get(
          item.url,
          minDelay: Duration(milliseconds: (wait * 1000).round()),
        );
        fetched++;

        final page = <String, Object?>{
          'url': item.url,
          'final_url': res.finalUrl,
          'depth': item.depth,
          'status_code': res.status,
          'content_type': res.contentType,
          'fetched_at': nowSeconds(),
          'title': '',
          'description': '',
          'text': '',
          'error': res.error,
        };

        if (res.error == null && res.isHtml) {
          final parsed = parseHtml(res.text, res.finalUrl);
          page['title'] = parsed.title;
          page['description'] = parsed.description;
          page['text'] = parsed.text;
          if (item.depth < maxDepth) {
            for (final link in parsed.links) {
              if (sameSite && Uri.parse(link).host != siteHost) continue;
              if (seen.add(link)) queue.add(_Item(link, item.depth + 1));
            }
          }
        }

        await db.addPage(jobId, page);
        await db.updateJob(jobId, {'pages_fetched': fetched});
        onChange?.call();
      }
    } catch (e) {
      status = 'failed';
      error = '${e.runtimeType}: $e';
    }

    await db.updateJob(jobId, {
      'status': status,
      'error': error,
      'pages_fetched': fetched,
      'finished_at': nowSeconds(),
    });
    onChange?.call();
  }
}
