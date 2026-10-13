import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';

import '../data/repository.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  void _snack(BuildContext context, String text) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  Future<void> _backup(BuildContext context) async {
    try {
      final file = await repo.makeBackup();
      await Share.shareXFiles([XFile(file.path)], subject: 'Scraper backup');
    } catch (e) {
      if (context.mounted) _snack(context, 'Backup failed: $e');
    }
  }

  Future<void> _restore(BuildContext context) async {
    final picked = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: const ['zip'],
    );
    final path = picked?.files.single.path;
    if (path == null) return;
    try {
      await repo.restore(File(path));
      if (context.mounted) _snack(context, 'Backup restored.');
    } catch (e) {
      if (context.mounted) _snack(context, 'Restore failed: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        children: [
          const _SectionHeader('Backup'),
          ListTile(
            leading: const Icon(Icons.backup_outlined),
            title: const Text('Create backup'),
            subtitle: const Text('Save a zip with your full database to any app or cloud'),
            onTap: () => _backup(context),
          ),
          ListTile(
            leading: const Icon(Icons.restore),
            title: const Text('Restore from backup'),
            subtitle: const Text('Replaces current data. Stop running scrapes first.'),
            onTap: () => _restore(context),
          ),
          const _SectionHeader('About'),
          ListTile(
            leading: const Icon(Icons.badge_outlined),
            title: const Text('User agent'),
            subtitle: Text(userAgent),
          ),
          const ListTile(
            leading: Icon(Icons.shield_outlined),
            title: Text('Polite by design'),
            subtitle: Text(
              'Reads robots.txt, waits between requests, stays on public '
              'addresses, and never logs in or bypasses blocks.',
            ),
          ),
        ],
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Text(
        text.toUpperCase(),
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w700,
          color: Theme.of(context).colorScheme.primary,
        ),
      ),
    );
  }
}
