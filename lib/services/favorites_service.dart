import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';

class FavoriteContact {
  final String name;
  final String upiId;
  final DateTime lastPaid;

  FavoriteContact({
    required this.name,
    required this.upiId,
    required this.lastPaid,
  });

  Map<String, dynamic> toJson() => {
    'name': name,
    'upiId': upiId,
    'lastPaid': lastPaid.toIso8601String(),
  };

  factory FavoriteContact.fromJson(Map<String, dynamic> json) => FavoriteContact(
    name: json['name'] ?? '',
    upiId: json['upiId'] ?? '',
    lastPaid: DateTime.parse(json['lastPaid']),
  );
}

class FavoritesService {
  static const _key = 'favorites';

  static Future<List<FavoriteContact>> getAll() async {
    final prefs = await SharedPreferences.getInstance();
    final list = prefs.getStringList(_key) ?? [];
    return list.map((s) => FavoriteContact.fromJson(jsonDecode(s))).toList();
  }

  static Future<void> add(FavoriteContact contact) async {
    final prefs = await SharedPreferences.getInstance();
    final list = prefs.getStringList(_key) ?? [];
    // Remove existing entry with same upiId to avoid duplicates
    list.removeWhere((s) {
      final c = FavoriteContact.fromJson(jsonDecode(s));
      return c.upiId == contact.upiId;
    });
    list.insert(0, jsonEncode(contact.toJson()));
    await prefs.setStringList(_key, list);
  }

  static Future<void> remove(String upiId) async {
    final prefs = await SharedPreferences.getInstance();
    final list = prefs.getStringList(_key) ?? [];
    list.removeWhere((s) {
      final c = FavoriteContact.fromJson(jsonDecode(s));
      return c.upiId == upiId;
    });
    await prefs.setStringList(_key, list);
  }

  static Future<bool> isFavorite(String upiId) async {
    final all = await getAll();
    return all.any((c) => c.upiId == upiId);
  }
}
