import 'dart:convert';
import 'package:http/http.dart' as http;

class AppUpdate {
  final String version;
  final String downloadUrl;
  final String releaseNotes;

  AppUpdate({
    required this.version,
    required this.downloadUrl,
    required this.releaseNotes,
  });
}

class UpdateService {
  static const _repo = 'xenondevv/Switch-Pay';
  static const _apiUrl = 'https://api.github.com/repos/$_repo/releases/latest';

  /// Check for updates. Returns [AppUpdate] if a newer version exists, null otherwise.
  static Future<AppUpdate?> checkForUpdate(String currentVersion) async {
    try {
      final response = await http.get(
        Uri.parse(_apiUrl),
        headers: {'Accept': 'application/vnd.github.v3+json'},
      ).timeout(const Duration(seconds: 10));

      if (response.statusCode != 200) return null;

      final data = jsonDecode(response.body) as Map<String, dynamic>;
      final tagName = (data['tag_name'] as String?) ?? '';
      final remoteVersion = tagName.replaceAll('v', '').trim();

      if (remoteVersion.isEmpty) return null;
      if (!_isNewer(remoteVersion, currentVersion)) return null;

      // Find APK asset
      String downloadUrl = '';
      final assets = data['assets'] as List<dynamic>? ?? [];
      for (final asset in assets) {
        final name = (asset['name'] as String?) ?? '';
        if (name.endsWith('.apk')) {
          downloadUrl = (asset['browser_download_url'] as String?) ?? '';
          break;
        }
      }

      // Fallback to release page if no APK asset
      if (downloadUrl.isEmpty) {
        downloadUrl = (data['html_url'] as String?) ?? '';
      }

      return AppUpdate(
        version: remoteVersion,
        downloadUrl: downloadUrl,
        releaseNotes: (data['body'] as String?) ?? 'New version available.',
      );
    } catch (_) {
      return null;
    }
  }

  /// Compare semantic versions. Returns true if remote > current.
  static bool _isNewer(String remote, String current) {
    final r = remote.split('.').map((s) => int.tryParse(s) ?? 0).toList();
    final c = current.split('.').map((s) => int.tryParse(s) ?? 0).toList();

    // Pad to same length
    while (r.length < 3) r.add(0);
    while (c.length < 3) c.add(0);

    for (int i = 0; i < 3; i++) {
      if (r[i] > c[i]) return true;
      if (r[i] < c[i]) return false;
    }
    return false;
  }
}
