import 'package:flutter/material.dart';

import '../core/crawler.dart';
import '../data/repository.dart';
import 'job_screen.dart';

class NewJobScreen extends StatefulWidget {
  const NewJobScreen({super.key});

  @override
  State<NewJobScreen> createState() => _NewJobScreenState();
}

class _NewJobScreenState extends State<NewJobScreen> {
  final _url = TextEditingController();
  final _maxPages = TextEditingController(text: '50');
  final _maxDepth = TextEditingController(text: '2');
  final _delay = TextEditingController(text: '1');
  bool _sameSite = true;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _url.dispose();
    _maxPages.dispose();
    _maxDepth.dispose();
    _delay.dispose();
    super.dispose();
  }

  Future<void> _start() async {
    final settings = _readSettings();
    if (settings == null) return;
    setState(() => _busy = true);
    final id = await repo.db.createJob(
      startUrl: settings.startUrl,
      maxPages: settings.maxPages,
      maxDepth: settings.maxDepth,
      delay: settings.delay,
      sameSite: settings.sameSite,
      createdAt: nowSeconds(),
    );
    repo.startJob(id);
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => JobScreen(jobId: id)),
    );
  }

  JobSettings? _readSettings() {
    try {
      final settings = validateJobSettings(
        url: _url.text,
        maxPages: int.tryParse(_maxPages.text.trim()) ?? -1,
        maxDepth: int.tryParse(_maxDepth.text.trim()) ?? -1,
        delay: double.tryParse(_delay.text.trim()) ?? -1,
        sameSite: _sameSite,
      );
      setState(() => _error = null);
      return settings;
    } on FormatException catch (e) {
      setState(() => _error = e.message);
      return null;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('New scrape')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(
            'Give it a public website. It reads robots.txt, waits between '
            'requests, and stores titles, descriptions and text.',
            style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _url,
            keyboardType: TextInputType.url,
            autocorrect: false,
            decoration: const InputDecoration(
              labelText: 'Website address',
              hintText: 'https://example.com',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(child: _numField(_maxPages, 'Max pages')),
              const SizedBox(width: 12),
              Expanded(child: _numField(_maxDepth, 'Max depth')),
            ],
          ),
          const SizedBox(height: 12),
          _numField(_delay, 'Delay between requests (s)', decimal: true),
          const SizedBox(height: 4),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Stay on the same domain'),
            value: _sameSite,
            onChanged: (v) => setState(() => _sameSite = v),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: _busy ? null : _start,
            icon: const Icon(Icons.play_arrow),
            label: const Text('Start scraping'),
          ),
        ],
      ),
    );
  }

  Widget _numField(TextEditingController c, String label, {bool decimal = false}) {
    return TextField(
      controller: c,
      keyboardType: TextInputType.numberWithOptions(decimal: decimal),
      decoration: InputDecoration(labelText: label, border: const OutlineInputBorder()),
    );
  }
}
