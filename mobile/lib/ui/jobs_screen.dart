import 'package:flutter/material.dart';

import '../data/repository.dart';
import 'common.dart';
import 'job_screen.dart';
import 'new_job_screen.dart';

class JobsScreen extends StatelessWidget {
  const JobsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Scrapes')),
      floatingActionButton: FloatingActionButton.extended(
        icon: const Icon(Icons.add),
        label: const Text('New scrape'),
        onPressed: () => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => const NewJobScreen()),
        ),
      ),
      body: ListenableBuilder(
        listenable: repo,
        builder: (context, _) => FutureBuilder<List<Map<String, Object?>>>(
          future: repo.db.listJobs(),
          builder: (context, snap) {
            if (!snap.hasData) {
              return const Center(child: CircularProgressIndicator());
            }
            final jobs = snap.data!;
            if (jobs.isEmpty) {
              return const EmptyState(
                icon: Icons.travel_explore,
                text: 'No scrapes yet.\nTap "New scrape" to start one.',
              );
            }
            return ListView.separated(
              padding: const EdgeInsets.fromLTRB(12, 12, 12, 96),
              itemCount: jobs.length,
              separatorBuilder: (_, __) => const SizedBox(height: 8),
              itemBuilder: (context, i) => JobTile(job: jobs[i]),
            );
          },
        ),
      ),
    );
  }
}

class JobTile extends StatelessWidget {
  const JobTile({super.key, required this.job});

  final Map<String, Object?> job;

  @override
  Widget build(BuildContext context) {
    final id = job['id'] as int;
    return Card(
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
        title: Text(
          job['start_url'] as String,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontWeight: FontWeight.w600),
        ),
        subtitle: Text(
          '#$id · ${fmtTime(job['created_at'])} · ${job['pages_fetched']} pages',
        ),
        trailing: StatusChip(status: job['status'] as String),
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => JobScreen(jobId: id)),
        ),
      ),
    );
  }
}
