import 'package:flutter/material.dart';
import '../models/payment_request.dart';

class ScannerScreen extends StatefulWidget {
  const ScannerScreen({Key? key}) : super(key: key);

  @override
  State<ScannerScreen> createState() => _ScannerScreenState();
}

class _ScannerScreenState extends State<ScannerScreen> {
  final TextEditingController _qrInputController = TextEditingController();
  String? _parsedUpiId;
  String? _parsedName;
  String? _parsedAmount;
  bool _isValidQr = false;

  void _parseQrInput() {
    final input = _qrInputController.text.trim();
    if (input.isEmpty) return;

    try {
      final req = PaymentRequest.fromQrData(input);
      setState(() {
        _parsedUpiId = req.target;
        _parsedName = req.recipientName ?? 'Unknown';
        _parsedAmount = req.amount.isEmpty ? null : req.amount;
        _isValidQr = req.target.isNotEmpty;
      });
    } catch (_) {
      setState(() {
        _isValidQr = false;
        _parsedUpiId = null;
      });
    }
  }

  void _proceedToPayment() {
    if (!_isValidQr) return;
    Navigator.pop(context, PaymentRequest(
      target: _parsedUpiId!,
      amount: _parsedAmount ?? '',
      recipientName: _parsedName,
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0D1117),
      appBar: AppBar(
        title: const Text('Scan QR Code'),
        backgroundColor: const Color(0xFF161B22),
        foregroundColor: Colors.white,
        elevation: 0,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Camera placeholder
            Container(
              height: 280,
              decoration: BoxDecoration(
                color: const Color(0xFF161B22),
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: const Color(0xFF30363D), width: 1),
              ),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.qr_code_scanner, size: 80, color: Colors.green.shade400),
                  const SizedBox(height: 16),
                  Text(
                    'Camera QR Scanner',
                    style: TextStyle(color: Colors.grey.shade400, fontSize: 16),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Requires mobile_scanner package',
                    style: TextStyle(color: Colors.grey.shade600, fontSize: 12),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Use text input below to test',
                    style: TextStyle(color: Colors.green.shade400, fontSize: 12),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),

            // Manual QR input for testing
            Text(
              'Paste UPI QR Data',
              style: TextStyle(
                color: Colors.grey.shade300,
                fontSize: 14,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _qrInputController,
              style: const TextStyle(color: Colors.white, fontSize: 14),
              maxLines: 2,
              decoration: InputDecoration(
                hintText: 'upi://pay?pa=merchant@upi&pn=Shop&am=100',
                hintStyle: TextStyle(color: Colors.grey.shade700, fontSize: 13),
                filled: true,
                fillColor: const Color(0xFF161B22),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide(color: Colors.grey.shade800),
                ),
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: const BorderSide(color: Color(0xFF30363D)),
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide(color: Colors.green.shade400),
                ),
              ),
            ),
            const SizedBox(height: 12),
            SizedBox(
              height: 48,
              child: ElevatedButton(
                onPressed: _parseQrInput,
                style: ElevatedButton.styleFrom(
                  backgroundColor: const Color(0xFF238636),
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                ),
                child: const Text('Parse QR', style: TextStyle(fontWeight: FontWeight.w600)),
              ),
            ),

            // Parsed info
            if (_isValidQr) ...[
              const SizedBox(height: 24),
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: const Color(0xFF161B22),
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: Colors.green.shade800),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.check_circle, color: Colors.green.shade400, size: 20),
                        const SizedBox(width: 8),
                        Text('QR Parsed', style: TextStyle(
                          color: Colors.green.shade400, fontWeight: FontWeight.w600, fontSize: 16)),
                      ],
                    ),
                    const SizedBox(height: 12),
                    _infoRow('Recipient', _parsedName ?? 'Unknown'),
                    _infoRow('UPI ID', _parsedUpiId ?? ''),
                    _infoRow('Amount', _parsedAmount != null ? '₹$_parsedAmount' : 'Not set'),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              SizedBox(
                height: 52,
                child: ElevatedButton(
                  onPressed: _proceedToPayment,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.green.shade400,
                    foregroundColor: Colors.black,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  ),
                  child: const Text('Proceed to Pay', style: TextStyle(
                    fontWeight: FontWeight.w700, fontSize: 16)),
                ),
              ),
            ],

            if (!_isValidQr && _qrInputController.text.isNotEmpty) ...[
              const SizedBox(height: 16),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.red.shade900.withOpacity(0.3),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Row(
                  children: [
                    Icon(Icons.error_outline, color: Colors.red.shade400, size: 20),
                    const SizedBox(width: 8),
                    Expanded(child: Text('Invalid UPI QR data',
                        style: TextStyle(color: Colors.red.shade300))),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _infoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: TextStyle(color: Colors.grey.shade500, fontSize: 14)),
          Flexible(child: Text(value, style: const TextStyle(color: Colors.white, fontSize: 14),
              textAlign: TextAlign.end)),
        ],
      ),
    );
  }
}
