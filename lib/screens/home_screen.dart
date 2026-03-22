import 'dart:async';
import 'package:flutter/material.dart';
import '../models/payment_request.dart';
import '../services/platform_channel.dart';
import 'scanner_screen.dart';
import 'manual_pay_screen.dart';
import 'result_screen.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({Key? key}) : super(key: key);

  void _openScanner(BuildContext context) async {
    final result = await Navigator.push<PaymentRequest>(
      context,
      MaterialPageRoute(builder: (_) => const ScannerScreen()),
    );
    if (result != null && result.isValid) {
      Navigator.push(context, MaterialPageRoute(
        builder: (_) => ResultScreen(target: result.target, amount: result.amount, mode: 'USSD'),
      ));
    }
  }

  void _openManualPay(BuildContext context) {
    Navigator.push(context, MaterialPageRoute(builder: (_) => const ManualPayScreen()));
  }

  void _checkBalance(BuildContext context) {
    Navigator.push(context, MaterialPageRoute(
      builder: (_) => const ResultScreen(target: 'BALANCE', amount: '0', mode: 'USSD'),
    ));
  }

  void _ivrPay(BuildContext context) {
    Navigator.push(context, MaterialPageRoute(builder: (_) => const ManualPayScreen(forceIvr: true)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0D1117),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(height: 8),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Offline Pay', style: TextStyle(
                        color: Colors.green.shade400, fontSize: 28,
                        fontWeight: FontWeight.w800, letterSpacing: -0.5)),
                      const SizedBox(height: 4),
                      Text('No internet required', style: TextStyle(
                        color: Colors.grey.shade500, fontSize: 14)),
                    ],
                  ),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(
                      color: Colors.green.shade400.withOpacity(0.15),
                      borderRadius: BorderRadius.circular(20)),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Container(width: 8, height: 8,
                          decoration: BoxDecoration(
                            color: Colors.green.shade400, shape: BoxShape.circle)),
                        const SizedBox(width: 6),
                        Text('Ready', style: TextStyle(
                          color: Colors.green.shade400, fontSize: 12, fontWeight: FontWeight.w600)),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 28),

              // Actions
              Row(
                children: [
                  _actionCard(icon: Icons.qr_code_scanner, label: 'Scan QR',
                    color: Colors.purple.shade400, onTap: () => _openScanner(context)),
                  const SizedBox(width: 14),
                  _actionCard(icon: Icons.send_rounded, label: 'Send Money',
                    color: Colors.green.shade400, onTap: () => _openManualPay(context)),
                ],
              ),
              const SizedBox(height: 14),
              Row(
                children: [
                  _actionCard(icon: Icons.account_balance_wallet, label: 'Balance',
                    color: Colors.blue.shade400, onTap: () => _checkBalance(context)),
                  const SizedBox(width: 14),
                  _actionCard(icon: Icons.phone_in_talk, label: 'IVR Pay',
                    color: Colors.orange.shade400, onTap: () => _ivrPay(context)),
                ],
              ),
              const SizedBox(height: 28),

              Text('Quick Send', style: TextStyle(
                color: Colors.grey.shade300, fontSize: 18, fontWeight: FontWeight.w700)),
              const SizedBox(height: 14),
              Row(
                children: ['100', '200', '500'].map((amt) => Expanded(
                  child: Padding(
                    padding: EdgeInsets.only(right: amt != '500' ? 10 : 0),
                    child: GestureDetector(
                      onTap: () => _openManualPay(context),
                      child: Container(
                        padding: const EdgeInsets.symmetric(vertical: 18),
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.topLeft, end: Alignment.bottomRight,
                            colors: [const Color(0xFF161B22), Colors.green.shade900.withOpacity(0.2)]),
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: const Color(0xFF30363D))),
                        child: Column(
                          children: [
                            Text('₹$amt', style: TextStyle(
                              color: Colors.green.shade400, fontSize: 22, fontWeight: FontWeight.w800)),
                            const SizedBox(height: 4),
                            Text('Tap to send', style: TextStyle(
                              color: Colors.grey.shade600, fontSize: 11)),
                          ],
                        ),
                      ),
                    ),
                  ),
                )).toList(),
              ),
              const SizedBox(height: 28),

              // How it works
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  color: const Color(0xFF161B22),
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: const Color(0xFF30363D))),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('How it works', style: TextStyle(
                      color: Colors.grey.shade300, fontSize: 16, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 14),
                    _stepItem('1', 'Tries USSD in-app first', Colors.green.shade400),
                    _stepItem('2', 'If USSD fails → auto-switches to IVR', Colors.orange.shade400),
                    _stepItem('3', 'IVR auto-fills your details via DTMF', Colors.blue.shade400),
                    _stepItem('4', 'Only UPI PIN is manual (for safety)', Colors.purple.shade400),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _actionCard({required IconData icon, required String label,
    required Color color, required VoidCallback onTap}) {
    return Expanded(
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 26),
          decoration: BoxDecoration(
            color: const Color(0xFF161B22), borderRadius: BorderRadius.circular(20),
            border: Border.all(color: const Color(0xFF30363D))),
          child: Column(
            children: [
              Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: color.withOpacity(0.12), shape: BoxShape.circle),
                child: Icon(icon, color: color, size: 28)),
              const SizedBox(height: 12),
              Text(label, style: const TextStyle(
                color: Colors.white, fontSize: 14, fontWeight: FontWeight.w600)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _stepItem(String num, String text, Color color) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Container(
            width: 24, height: 24,
            decoration: BoxDecoration(
              color: color.withOpacity(0.15), shape: BoxShape.circle),
            child: Center(child: Text(num, style: TextStyle(
              color: color, fontSize: 12, fontWeight: FontWeight.w700)))),
          const SizedBox(width: 12),
          Expanded(child: Text(text, style: TextStyle(
            color: Colors.grey.shade400, fontSize: 13))),
        ],
      ),
    );
  }
}
