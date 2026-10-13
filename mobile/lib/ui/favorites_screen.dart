import 'package:flutter/material.dart';

import '../data/repository.dart';
import 'common.dart';
import 'job_screen.dart';
import 'page_screen.dart';

class FavoritesScreen extends StatefulWidget {
  const FavoritesScreen({super.key});

  @override
  State<FavoritesScreen> createState() => _FavoritesScreenState();
}

class _FavoritesScreenState extends State<FavoritesScreen> {
  String _kind = 'page';

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Favorites'),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(64),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: DropdownButtonFormField<String>(
              value: _kind,
              decoration: const InputDecoration(
                labelText: 'Show',
                border: OutlineInputBorder(),
                isDense: true,
              ),
              items: const [
                DropdownMenuItem(value: 'page', child: Text('Pages')),
                DropdownMenuItem(value: 'job', child: Text('Websites (scrapes)')),
              ],
              onChanged: (v) => setState(() => _kind = v ?? 'page'),
            ),
          ),
        ),
      ),
      body: ListenableBuilder(
        listenable: repo,
        builder: (context, _) => FutureBuilder<List<Map<String, Object?>>>(
          future: repo.db.listFavorites(_kind),
          builder: (context, snap) {
            if (!snap.hasData) {
              return const Center(child: CircularProgressIndicator());
            }
            final items = snap.data!;
            if (items.isEmpty) {
              return EmptyState(
                icon: Icons.star_outline,
                text: _kind == 'page'
                    ? 'No favourite pages yet.\nStar a page to see it here.'
                    : 'No favourite websites yet.\nStar a scrape to see it here.',
              );
            }
            return ListView.separated(
              padding: const EdgeInsets.all(12),
              itemCount: items.length,
              separatorBuilder: (_, __) => const SizedBox(height: 8),
              itemBuilder: (context, i) => _kind == 'page'
                  ? _pageCard(context, items[i])
                  : _jobCard(context, items[i]),
            );
          },
        ),
      ),
    );
  }

  Widget _pageCard(BuildContext context, Map<String, Object?> it) {
    final title = (it['title'] as String?)?.trim();
    return Card(
      child: ListTile(
        title: Text(
          (title == null || title.isEmpty) ? '(no title)' : title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        subtitle: Text(
          '${it['url']}\nScrape #${it['job_id']} · ${fmtTime(it['favorited_at'])}',
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
        isThreeLine: true,
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => PageScreen(pageId: it['id'] as int)),
        ),
      ),
    );
  }

  Widget _jobCard(BuildContext context, Map<String, Object?> it) {
    final id = it['id'] as int;
    return Card(
      child: ListTile(
        title: Text(
          it['start_url'] as String,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        subtitle: Text('#$id · ${it['pages_fetched']} pages · ${fmtTime(it['favorited_at'])}'),
        trailing: StatusChip(status: it['status'] as String),
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => JobScreen(jobId: id)),
        ),
      ),
    );
  }
}
