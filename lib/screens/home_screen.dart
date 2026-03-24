import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../models/payment_request.dart';
import '../services/platform_channel.dart';
import 'scanner_screen.dart';
import 'manual_pay_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({Key? key}) : super(key: key);

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  bool _accessibilityEnabled = false;

  @override
  void initState() {
    super.initState();
    _checkAccessibility();
  }

  Future<void> _checkAccessibility() async {
    final enabled = await NativeBridge.checkAccessibilityEnabled();
    if (mounted) setState(() => _accessibilityEnabled = enabled);
  }

  void _openScanner(BuildContext context) async {
    final result = await Navigator.push<PaymentRequest>(
      context,
      MaterialPageRoute(builder: (_) => const ScannerScreen()),
    );
    if (result != null && result.target.isNotEmpty && context.mounted) {
      Navigator.push(context, MaterialPageRoute(
        builder: (_) => ManualPayScreen(
          prefillTarget: result.target,
          prefillAmount: result.amount,
          prefillType: result.paymentType,
          prefillName: result.recipientName,
        ),
      ));
    }
  }

  void _openPay(BuildContext context) {
    Navigator.push(context, MaterialPageRoute(
      builder: (_) => const ManualPayScreen(),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(height: 8),
              // Header
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Row(
                    children: [
                      Image.asset('assets/spay_logo.png', width: 40, height: 40),
                      const SizedBox(width: 12),
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('Switch Pay', style: GoogleFonts.outfit(
                            color: Colors.white, fontSize: 26,
                            fontWeight: FontWeight.w900, letterSpacing: -0.5)),
                          const SizedBox(height: 3),
                          Text('No internet required • USSD *99#', style: TextStyle(
                            color: Colors.grey.shade600, fontSize: 12)),
                        ],
                      ),
                    ],
                  ),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(
                      color: (_accessibilityEnabled
                          ? Colors.white
                          : Colors.orange.shade400).withOpacity(0.12),
                      borderRadius: BorderRadius.circular(20)),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Container(width: 8, height: 8,
                          decoration: BoxDecoration(
                            color: _accessibilityEnabled
                                ? Colors.white
                                : Colors.orange.shade400,
                            shape: BoxShape.circle)),
                        const SizedBox(width: 6),
                        Text(_accessibilityEnabled ? 'Ready' : 'Setup',
                          style: TextStyle(
                            color: _accessibilityEnabled
                                ? Colors.white
                                : Colors.orange.shade400,
                            fontSize: 12, fontWeight: FontWeight.w600)),
                      ],
                    ),
                  ),
                ],
              ),

              // Accessibility warning banner
              if (!_accessibilityEnabled) ...[
                const SizedBox(height: 16),
                GestureDetector(
                  onTap: () async {
                    await NativeBridge.openAccessibilitySettings();
                    Future.delayed(const Duration(seconds: 1), _checkAccessibility);
                  },
                  child: Container(
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: Colors.orange.shade400.withOpacity(0.08),
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(color: Colors.orange.shade400.withOpacity(0.25)),
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.warning_amber_rounded, color: Colors.orange.shade400, size: 22),
                        const SizedBox(width: 10),
                        Expanded(child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('Enable Accessibility Service', style: TextStyle(
                              color: Colors.orange.shade300, fontSize: 13, fontWeight: FontWeight.w600)),
                            const SizedBox(height: 2),
                            Text('Required for automatic USSD navigation. Tap to open Settings.',
                              style: TextStyle(color: Colors.orange.shade200.withOpacity(0.7), fontSize: 11)),
                          ],
                        )),
                        Icon(Icons.arrow_forward_ios, color: Colors.orange.shade400, size: 14),
                      ],
                    ),
                  ),
                ),
              ],

              const SizedBox(height: 24),

              // 2 Action cards
              _actionCard(
                icon: Icons.send_rounded,
                label: 'Send Money',
                subtitle: 'Enter UPI ID',
                color: Colors.white,
                onTap: () => _openPay(context),
              ),
              const SizedBox(height: 12),
              _actionCard(
                icon: Icons.qr_code_scanner,
                label: 'Scan QR',
                subtitle: 'Scan UPI QR code',
                color: Colors.grey.shade300,
                onTap: () => _openScanner(context),
              ),
              const SizedBox(height: 28),

              // Quick Send
              const Text('Quick Send', style: TextStyle(
                color: Colors.white, fontSize: 18, fontWeight: FontWeight.w700)),
              const SizedBox(height: 14),
              Row(
                children: ['100', '200', '500'].map((amt) => Expanded(
                  child: Padding(
                    padding: EdgeInsets.only(right: amt != '500' ? 10 : 0),
                    child: GestureDetector(
                      onTap: () {
                        Navigator.push(context, MaterialPageRoute(
                          builder: (_) => ManualPayScreen(
                            prefillAmount: amt,
                          ),
                        ));
                      },
                      child: Container(
                        padding: const EdgeInsets.symmetric(vertical: 18),
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.topLeft, end: Alignment.bottomRight,
                            colors: [const Color(0xFF111111), Colors.grey.shade900.withOpacity(0.3)]),
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: Colors.grey.shade800)),
                        child: Column(
                          children: [
                            Text('₹$amt', style: const TextStyle(
                              color: Colors.white, fontSize: 22, fontWeight: FontWeight.w800)),
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
                  color: const Color(0xFF111111),
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: Colors.grey.shade800)),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('How it works', style: TextStyle(
                      color: Colors.white, fontSize: 16, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 14),
                    _stepItem('1', 'Dials *99# USSD automatically', Colors.white),
                    _stepItem('2', 'Navigates bank menus for you', Colors.grey.shade400),
                    _stepItem('3', 'Fills in recipient & amount', Colors.grey.shade300),
                    _stepItem('4', 'You enter UPI PIN securely', Colors.white70),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _actionCard({
    required IconData icon,
    required String label,
    required String subtitle,
    required Color color,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 18),
        decoration: BoxDecoration(
          color: const Color(0xFF111111),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: Colors.grey.shade800),
        ),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: color.withOpacity(0.1),
                borderRadius: BorderRadius.circular(14)),
              child: Icon(icon, color: color, size: 26)),
            const SizedBox(width: 16),
            Expanded(child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: const TextStyle(
                  color: Colors.white, fontSize: 16, fontWeight: FontWeight.w600)),
                const SizedBox(height: 3),
                Text(subtitle, style: TextStyle(
                  color: Colors.grey.shade500, fontSize: 12)),
              ],
            )),
            Icon(Icons.arrow_forward_ios, color: Colors.grey.shade700, size: 16),
          ],
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
              color: color.withOpacity(0.12), shape: BoxShape.circle),
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
