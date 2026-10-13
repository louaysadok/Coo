import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/crawler.dart';
import '../data/repository.dart';
import 'common.dart';

class PageScreen extends StatefulWidget {
  const PageScreen({super.key, required this.pageId});

  final int pageId;

  @override
  State<PageScreen> createState() => _PageScreenState();
}

class _PageScreenState extends State<PageScreen> {
  Map<String, Object?>? _page;
  bool _fav = false;
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    repo.addListener(_load);
    _load();
  }

  @override
  void dispose() {
    repo.removeListener(_load);
    super.dispose();
  }

  Future<void> _load() async {
    final page = await repo.db.getPage(widget.pageId);
    final favs = await repo.db.favoriteIds('page');
    if (!mounted) return;
    setState(() {
      _page = page;
      _fav = favs.contains(widget.pageId);
      _loaded = true;
    });
  }

  Future<void> _toggle() async {
    await repo.db.toggleFavorite('page', widget.pageId, nowSeconds());
    repo.touch();
  }

  @override
  Widget build(BuildContext context) {
    final page = _page;
    if (!_loaded) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (page == null) {
      return const Scaffold(body: EmptyState(icon: Icons.error_outline, text: 'Page not found.'));
    }
    final scheme = Theme.of(context).colorScheme;
    final title = (page['title'] as String?)?.trim();
    final url = page['url'] as String;
    final text = (page['text'] as String?) ?? '';
    return Scaffold(
      appBar: AppBar(
        title: const Text('Page'),
        actions: [
          IconButton(
            tooltip: 'Copy URL',
            icon: const Icon(Icons.link),
            onPressed: () {
              Clipboard.setData(ClipboardData(text: url));
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('URL copied')),
              );
            },
          ),
          IconButton(
            tooltip: _fav ? 'Remove favourite' : 'Favourite',
            icon: Icon(_fav ? Icons.star : Icons.star_outline,
                color: _fav ? const Color(0xFFFBBF24) : null),
            onPressed: _toggle,
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(
            (title == null || title.isEmpty) ? '(no title)' : title,
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 6),
          SelectableText(url, style: TextStyle(color: scheme.primary)),
          if ((page['description'] as String?)?.isNotEmpty ?? false) ...[
            const SizedBox(height: 10),
            Text(page['description'] as String),
          ],
          const SizedBox(height: 12),
          Wrap(
            spacing: 16,
            children: [
              _stat('${page['status_code'] ?? '—'}', 'status'),
              _stat('${page['depth']}', 'depth'),
              _stat('${page['content_type'] ?? '—'}', 'type'),
              _stat(fmtTime(page['fetched_at']), 'fetched'),
            ],
          ),
          if (page['error'] != null) ...[
            const SizedBox(height: 12),
            Text('${page['error']}', style: TextStyle(color: scheme.error)),
          ],
          const Divider(height: 32),
          Text('Extracted text', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          SelectableText(text.isEmpty ? 'No text extracted.' : text),
        ],
      ),
    );
  }

  Widget _stat(String value, String label) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(value, style: const TextStyle(fontWeight: FontWeight.w600)),
        Text(label, style: TextStyle(fontSize: 12, color: Theme.of(context).colorScheme.onSurfaceVariant)),
      ],
    );
  }
}
