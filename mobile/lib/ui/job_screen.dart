import 'dart:io';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

import '../core/crawler.dart';
import '../data/repository.dart';
import 'common.dart';
import 'page_screen.dart';

const _pageSize = 50;

class JobScreen extends StatefulWidget {
  const JobScreen({super.key, required this.jobId});

  final int jobId;

  @override
  State<JobScreen> createState() => _JobScreenState();
}

class _JobScreenState extends State<JobScreen> {
  final _search = TextEditingController();
  Map<String, Object?>? _job;
  List<Map<String, Object?>> _pages = [];
  int _total = 0;
  int _limit = _pageSize;
  Set<int> _favPages = {};
  bool _jobFav = false;
  bool _gone = false;

  @override
  void initState() {
    super.initState();
    repo.addListener(_load);
    _load();
  }

  @override
  void dispose() {
    repo.removeListener(_load);
    _search.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final job = await repo.db.getJob(widget.jobId);
    if (!mounted) return;
    if (job == null) {
      setState(() => _gone = true);
      return;
    }
    final res = await repo.db.listPages(
      widget.jobId,
      q: _search.text,
      limit: _limit,
    );
    final favPages = await repo.db.favoriteIds('page');
    final favJobs = await repo.db.favoriteIds('job');
    if (!mounted) return;
    setState(() {
      _job = job;
      _pages = res.rows;
      _total = res.total;
      _favPages = favPages;
      _jobFav = favJobs.contains(widget.jobId);
    });
  }

  Future<void> _toggleJobFavorite() async {
    await repo.db.toggleFavorite('job', widget.jobId, nowSeconds());
    repo.touch();
  }

  Future<void> _togglePageFavorite(int pageId) async {
    await repo.db.toggleFavorite('page', pageId, nowSeconds());
    repo.touch();
  }

  Future<void> _stop() => repo.db.requestCancel(widget.jobId);

  Future<void> _resume() async {
    await repo.db.resetForResume(widget.jobId);
    repo.startJob(widget.jobId);
    repo.touch();
  }

  Future<void> _delete() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete this scrape?'),
        content: const Text('All of its pages will be removed.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Delete')),
        ],
      ),
    );
    if (ok != true) return;
    await repo.db.deleteJob(widget.jobId);
    repo.touch();
    if (mounted) Navigator.of(context).pop();
  }

  Future<void> _exportCsv() async {
    final pages = await repo.db.allPages(widget.jobId);
    final buf = StringBuffer()
      ..writeln('url,final_url,depth,status_code,title,description,error,fetched_at');
    for (final p in pages) {
      buf.writeln([
        p['url'],
        p['final_url'],
        p['depth'],
        p['status_code'],
        p['title'],
        p['description'],
        p['error'],
        fmtTime(p['fetched_at']),
      ].map(_csvCell).join(','));
    }
    final dir = await getTemporaryDirectory();
    final file = File('${dir.path}/job-${widget.jobId}.csv');
    await file.writeAsString(buf.toString());
    await Share.shareXFiles([XFile(file.path)], subject: 'Scrape #${widget.jobId}');
  }

  @override
  Widget build(BuildContext context) {
    if (_gone) {
      return const Scaffold(body: EmptyState(icon: Icons.error_outline, text: 'This scrape no longer exists.'));
    }
    final job = _job;
    if (job == null) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    final status = job['status'] as String;
    final running = status == 'queued' || status == 'running' || repo.isRunning(widget.jobId);
    final scheme = Theme.of(context).colorScheme;
    final fetched = (job['pages_fetched'] as int?) ?? 0;
    final maxPages = job['max_pages'] as int;
    final progress = maxPages == 0 ? null : (fetched / maxPages).clamp(0.0, 1.0);

    return Scaffold(
      appBar: AppBar(
        title: Text('Scrape #${widget.jobId}'),
        actions: [
          IconButton(
            tooltip: _jobFav ? 'Remove favourite' : 'Favourite',
            icon: Icon(_jobFav ? Icons.star : Icons.star_outline,
                color: _jobFav ? const Color(0xFFFBBF24) : null),
            onPressed: _toggleJobFavorite,
          ),
          PopupMenuButton<String>(
            onSelected: (v) {
              if (v == 'csv') _exportCsv();
              if (v == 'delete') _delete();
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'csv', child: Text('Export CSV')),
              PopupMenuItem(value: 'delete', child: Text('Delete')),
            ],
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(12),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          job['start_url'] as String,
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ),
                      const SizedBox(width: 8),
                      StatusChip(status: status),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 16,
                    runSpacing: 8,
                    children: [
                      _stat('$fetched', 'pages'),
                      _stat('$maxPages', 'limit'),
                      _stat('${job['max_depth']}', 'depth'),
                      _stat('${job['delay']}s', 'delay'),
                      _stat(job['same_site'] == 1 ? 'Yes' : 'No', 'same domain'),
                    ],
                  ),
                  if (running) ...[
                    const SizedBox(height: 12),
                    LinearProgressIndicator(value: progress),
                  ],
                  if (job['error'] != null) ...[
                    const SizedBox(height: 12),
                    Text('${job['error']}', style: TextStyle(color: scheme.error)),
                  ],
                  const SizedBox(height: 12),
                  running
                      ? OutlinedButton.icon(
                          onPressed: _stop,
                          icon: const Icon(Icons.stop),
                          label: const Text('Stop'),
                        )
                      : FilledButton.tonalIcon(
                          onPressed: _resume,
                          icon: const Icon(Icons.refresh),
                          label: const Text('Resume / re-crawl'),
                        ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _search,
            decoration: InputDecoration(
              hintText: 'Search titles, text, URLs…',
              prefixIcon: const Icon(Icons.search),
              border: const OutlineInputBorder(),
              suffixIcon: _search.text.isEmpty
                  ? null
                  : IconButton(
                      icon: const Icon(Icons.clear),
                      onPressed: () {
                        _search.clear();
                        _load();
                      },
                    ),
            ),
            onSubmitted: (_) {
              _limit = _pageSize;
              _load();
            },
          ),
          const SizedBox(height: 8),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            child: Text('$_total pages', style: TextStyle(color: scheme.onSurfaceVariant)),
          ),
          if (_pages.isEmpty)
            Padding(
              padding: const EdgeInsets.all(24),
              child: Center(
                child: Text(
                  running ? 'Crawling… results will appear here.' : 'No pages match.',
                  style: TextStyle(color: scheme.onSurfaceVariant),
                ),
              ),
            ),
          for (final p in _pages) _pageTile(p),
          if (_pages.length < _total)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: OutlinedButton(
                onPressed: () {
                  _limit += _pageSize;
                  _load();
                },
                child: const Text('Load more'),
              ),
            ),
        ],
      ),
    );
  }

  Widget _stat(String value, String label) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(value, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
        Text(label, style: TextStyle(fontSize: 12, color: Theme.of(context).colorScheme.onSurfaceVariant)),
      ],
    );
  }

  Widget _pageTile(Map<String, Object?> p) {
    final id = p['id'] as int;
    final fav = _favPages.contains(id);
    final error = p['error'] as String?;
    final title = (p['title'] as String?)?.trim();
    final desc = p['description'] as String?;
    return Card(
      child: ListTile(
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => PageScreen(pageId: id)),
        ),
        title: Text(
          (title == null || title.isEmpty) ? '(no title)' : title,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(p['url'] as String, maxLines: 1, overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 12)),
            if (desc != null && desc.isNotEmpty)
              Text(desc, maxLines: 2, overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 12)),
            const SizedBox(height: 4),
            Text(
              error ?? 'depth ${p['depth']} · ${p['status_code'] ?? 'ok'}',
              style: TextStyle(
                fontSize: 11,
                color: error == null ? null : Theme.of(context).colorScheme.error,
              ),
            ),
          ],
        ),
        trailing: IconButton(
          tooltip: fav ? 'Remove favourite' : 'Favourite',
          icon: Icon(fav ? Icons.star : Icons.star_outline,
              color: fav ? const Color(0xFFFBBF24) : null),
          onPressed: () => _togglePageFavorite(id),
        ),
      ),
    );
  }
}

String _csvCell(Object? v) {
  final s = v?.toString() ?? '';
  return '"${s.replaceAll('"', '""')}"';
}
