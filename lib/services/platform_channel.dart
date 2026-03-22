import 'package:flutter/services.dart';
import 'dart:async';

class PaymentStep {
  final String status;
  final String message;
  final bool isFinal;
  final DateTime timestamp;

  PaymentStep({
    required this.status,
    required this.message,
    required this.isFinal,
  }) : timestamp = DateTime.now();
}

class NativeBridge {
  static const platform = MethodChannel('offline_payment_channel');
  static final StreamController<PaymentStep> _statusController =
      StreamController<PaymentStep>.broadcast();

  static Stream<PaymentStep> get statusStream => _statusController.stream;

  static void init() {
    platform.setMethodCallHandler((call) async {
      if (call.method == 'paymentStatus') {
        final status = call.arguments['status'] as String? ?? '';
        final message = call.arguments['message'] as String? ?? '';
        final isFinal = call.arguments['isFinal'] as bool? ?? false;
        _statusController.add(PaymentStep(
          status: status,
          message: message,
          isFinal: isFinal,
        ));
      }
    });
  }

  static Future<String> triggerOfflinePayment({
    required String target,
    required String amount,
    required String mode,
  }) async {
    try {
      final result = await platform.invokeMethod('executePayment', {
        "target": target,
        "amount": amount,
        "mode": mode,
      });
      return result.toString();
    } on PlatformException catch (e) {
      return "Error: ${e.message}";
    } catch (e) {
      return "Unexpected error: $e";
    }
  }
}
