import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';

class PaymentRecord {
  final String target;
  final String amount;
  final String? recipientName;
  final DateTime timestamp;
  final bool success;

  PaymentRecord({
    required this.target,
    required this.amount,
    this.recipientName,
    required this.timestamp,
    required this.success,
  });

  Map<String, dynamic> toJson() => {
    'target': target,
    'amount': amount,
    'recipientName': recipientName,
    'timestamp': timestamp.toIso8601String(),
    'success': success,
  };

  factory PaymentRecord.fromJson(Map<String, dynamic> json) => PaymentRecord(
    target: json['target'] ?? '',
    amount: json['amount'] ?? '0',
    recipientName: json['recipientName'],
    timestamp: DateTime.parse(json['timestamp']),
    success: json['success'] ?? false,
  );
}

class PaymentHistoryService {
  static const _key = 'payment_history';

  static Future<void> addRecord(PaymentRecord record) async {
    final prefs = await SharedPreferences.getInstance();
    final list = prefs.getStringList(_key) ?? [];
    list.insert(0, jsonEncode(record.toJson()));
    // Keep max 50 records
    if (list.length > 50) list.removeRange(50, list.length);
    await prefs.setStringList(_key, list);
  }

  static Future<List<PaymentRecord>> getRecords({int limit = 5}) async {
    final prefs = await SharedPreferences.getInstance();
    final list = prefs.getStringList(_key) ?? [];
    return list
        .take(limit)
        .map((s) => PaymentRecord.fromJson(jsonDecode(s)))
        .toList();
  }
}
