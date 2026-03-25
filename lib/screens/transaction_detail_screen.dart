import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../services/payment_history.dart';
import 'manual_pay_screen.dart';

class TransactionDetailScreen extends StatelessWidget {
  final PaymentRecord record;

  const TransactionDetailScreen({Key? key, required this.record}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final time = record.timestamp;
    final timeStr =
        '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
    final dateStr = '${time.day}/${time.month}/${time.year}';
    final statusColor =
        record.success ? Colors.green.shade400 : Colors.red.shade400;

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: Text('Transaction Details',
            style: GoogleFonts.outfit(fontWeight: FontWeight.w700, fontSize: 20)),
        backgroundColor: const Color(0xFF111111),
        foregroundColor: Colors.white,
        elevation: 0,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          children: [
            const SizedBox(height: 16),

            // Status icon
            Container(
              width: 80,
              height: 80,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: statusColor.withOpacity(0.12),
              ),
              child: Icon(
                record.success ? Icons.check_rounded : Icons.close_rounded,
                color: statusColor,
                size: 42,
              ),
            ),
            const SizedBox(height: 16),

            // Status text
            Text(
              record.success ? 'Payment Successful' : 'Payment Failed',
              style: TextStyle(
                color: statusColor,
                fontSize: 18,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 12),

            // Amount
            Text(
              '₹${record.amount}',
              style: const TextStyle(
                color: Colors.white,
                fontSize: 42,
                fontWeight: FontWeight.w800,
                letterSpacing: -2,
              ),
            ),
            const SizedBox(height: 32),

            // Details card
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(20),
              decoration: BoxDecoration(
                color: const Color(0xFF111111),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: Colors.grey.shade800),
              ),
              child: Column(
                children: [
                  _detailRow('Recipient',
                      record.recipientName ?? record.target),
                  if (record.recipientName != null) ...[
                    const SizedBox(height: 14),
                    Divider(color: Colors.grey.shade800, height: 1),
                    const SizedBox(height: 14),
                    _detailRow('UPI ID', record.target),
                  ],
                  const SizedBox(height: 14),
                  Divider(color: Colors.grey.shade800, height: 1),
                  const SizedBox(height: 14),
                  _detailRow('Amount', '₹${record.amount}'),
                  const SizedBox(height: 14),
                  Divider(color: Colors.grey.shade800, height: 1),
                  const SizedBox(height: 14),
                  _detailRow('Date', dateStr),
                  const SizedBox(height: 14),
                  Divider(color: Colors.grey.shade800, height: 1),
                  const SizedBox(height: 14),
                  _detailRow('Time', timeStr),
                  const SizedBox(height: 14),
                  Divider(color: Colors.grey.shade800, height: 1),
                  const SizedBox(height: 14),
                  _detailRow('Status',
                      record.success ? 'Successful' : 'Failed'),
                ],
              ),
            ),
            const SizedBox(height: 24),

            // Pay Again button
            SizedBox(
              width: double.infinity,
              height: 52,
              child: ElevatedButton(
                onPressed: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (_) => ManualPayScreen(
                        prefillTarget: record.target,
                        prefillName: record.recipientName,
                      ),
                    ),
                  );
                },
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.white,
                  foregroundColor: Colors.black,
                  shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14)),
                  elevation: 0,
                ),
                child: const Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.replay_rounded, size: 18),
                    SizedBox(width: 8),
                    Text('Pay Again',
                        style: TextStyle(
                            fontWeight: FontWeight.w700, fontSize: 16)),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _detailRow(String label, String value) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label,
            style: TextStyle(
                color: Colors.grey.shade500,
                fontSize: 13,
                fontWeight: FontWeight.w500)),
        const SizedBox(width: 16),
        Flexible(
          child: Text(value,
              style: const TextStyle(
                  color: Colors.white,
                  fontSize: 14,
                  fontWeight: FontWeight.w600),
              textAlign: TextAlign.right),
        ),
      ],
    );
  }
}
