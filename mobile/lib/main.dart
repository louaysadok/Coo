import 'package:flutter/material.dart';

import 'data/repository.dart';
import 'ui/home.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  repo = await Repository.open();
  runApp(const PoliteScraperApp());
}

class PoliteScraperApp extends StatelessWidget {
  const PoliteScraperApp({super.key});

  @override
  Widget build(BuildContext context) {
    const seed = Color(0xFF7C9CFF);
    return MaterialApp(
      title: 'Polite Scraper',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: seed,
        brightness: Brightness.light,
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: seed,
        brightness: Brightness.dark,
      ),
      home: const HomeShell(),
    );
  }
}
