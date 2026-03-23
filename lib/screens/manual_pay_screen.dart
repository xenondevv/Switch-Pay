import 'package:flutter/material.dart';
import 'result_screen.dart';

class ManualPayScreen extends StatefulWidget {
  final String? prefillTarget;
  final String? prefillAmount;
  final String? prefillName;
  final String prefillType;

  const ManualPayScreen({
    Key? key,
    this.prefillTarget,
    this.prefillAmount,
    this.prefillName,
    this.prefillType = 'phone',
  }) : super(key: key);

  @override
  State<ManualPayScreen> createState() => _ManualPayScreenState();
}

class _ManualPayScreenState extends State<ManualPayScreen> {
  final TextEditingController _targetController = TextEditingController();
  final TextEditingController _amountController = TextEditingController();
  final List<String> _presetAmounts = ['100', '200', '500', '1000', '2000'];

  @override
  void initState() {
    super.initState();
    if (widget.prefillTarget != null) _targetController.text = widget.prefillTarget!;
    if (widget.prefillAmount != null && widget.prefillAmount!.isNotEmpty) {
      _amountController.text = widget.prefillAmount!;
    }
  }

  void _sendPayment() {
    final target = _targetController.text.trim();
    final amount = _amountController.text.trim();

    if (target.isEmpty) {
      _showError('Enter phone number or UPI ID');
      return;
    }
    if (target.length < 3) {
      _showError('Enter a valid phone number or UPI ID');
      return;
    }
    if (amount.isEmpty || int.tryParse(amount) == null || int.parse(amount) <= 0) {
      _showError('Enter a valid amount');
      return;
    }

    Navigator.push(context, MaterialPageRoute(
      builder: (_) => ResultScreen(
        target: target,
        amount: amount,
        recipientName: widget.prefillName,
      ),
    ));
  }

  void _showError(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg),
      backgroundColor: Colors.red.shade800,
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0D1117),
      appBar: AppBar(
        title: const Text('Send Money'),
        backgroundColor: const Color(0xFF161B22),
        foregroundColor: Colors.white,
        elevation: 0,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // QR pre-fill info
            if (widget.prefillName != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.green.shade400.withOpacity(0.08),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.green.shade400.withOpacity(0.2)),
                ),
                child: Row(
                  children: [
                    Icon(Icons.qr_code, color: Colors.green.shade400, size: 18),
                    const SizedBox(width: 8),
                    Expanded(child: Text(
                      'QR Scanned: ${widget.prefillName}',
                      style: TextStyle(color: Colors.green.shade300, fontSize: 12),
                    )),
                  ],
                ),
              ),
              const SizedBox(height: 16),
            ],

            // Recipient — single field accepting both phone and UPI
            Text('Phone Number or UPI ID', style: TextStyle(
              color: Colors.grey.shade300, fontSize: 14, fontWeight: FontWeight.w600)),
            const SizedBox(height: 8),
            TextField(
              controller: _targetController,
              style: const TextStyle(color: Colors.white, fontSize: 16),
              keyboardType: TextInputType.text,
              decoration: InputDecoration(
                hintText: 'e.g. 9876543210 or name@bank',
                hintStyle: TextStyle(color: Colors.grey.shade700),
                prefixIcon: Icon(Icons.person_outline, color: Colors.grey.shade500),
                filled: true,
                fillColor: const Color(0xFF161B22),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12),
                  borderSide: const BorderSide(color: Color(0xFF30363D))),
                enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12),
                  borderSide: const BorderSide(color: Color(0xFF30363D))),
                focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide(color: Colors.green.shade400)),
              ),
            ),
            const SizedBox(height: 6),
            Text(
              'USSD *99# accepts both phone numbers and UPI IDs',
              style: TextStyle(color: Colors.grey.shade600, fontSize: 11),
            ),
            const SizedBox(height: 24),

            // Amount
            Text('Amount (₹)', style: TextStyle(
              color: Colors.grey.shade300, fontSize: 14, fontWeight: FontWeight.w600)),
            const SizedBox(height: 8),
            TextField(
              controller: _amountController,
              style: const TextStyle(color: Colors.white, fontSize: 28, fontWeight: FontWeight.w700),
              keyboardType: TextInputType.number,
              textAlign: TextAlign.center,
              decoration: InputDecoration(
                hintText: '0',
                hintStyle: TextStyle(color: Colors.grey.shade700, fontSize: 28),
                prefixText: '₹ ',
                prefixStyle: TextStyle(color: Colors.green.shade400, fontSize: 28, fontWeight: FontWeight.w700),
                filled: true,
                fillColor: const Color(0xFF161B22),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(16),
                  borderSide: const BorderSide(color: Color(0xFF30363D))),
                enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(16),
                  borderSide: const BorderSide(color: Color(0xFF30363D))),
                focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(16),
                  borderSide: BorderSide(color: Colors.green.shade400)),
              ),
            ),
            const SizedBox(height: 16),

            // Presets
            Wrap(
              spacing: 10,
              runSpacing: 10,
              children: _presetAmounts.map((amt) => GestureDetector(
                onTap: () => setState(() => _amountController.text = amt),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                  decoration: BoxDecoration(
                    color: _amountController.text == amt
                        ? Colors.green.shade400.withOpacity(0.2)
                        : const Color(0xFF161B22),
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                      color: _amountController.text == amt
                          ? Colors.green.shade400 : const Color(0xFF30363D)),
                  ),
                  child: Text('₹$amt', style: TextStyle(
                    color: _amountController.text == amt ? Colors.green.shade400 : Colors.white,
                    fontWeight: FontWeight.w600, fontSize: 15)),
                ),
              )).toList(),
            ),
            const SizedBox(height: 32),

            // Pay button
            SizedBox(
              height: 56,
              child: ElevatedButton(
                onPressed: _sendPayment,
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.green.shade400,
                  foregroundColor: Colors.black,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                  elevation: 0,
                ),
                child: const Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.send_rounded, size: 20),
                    SizedBox(width: 8),
                    Text('Send Payment', style: TextStyle(
                        fontWeight: FontWeight.w700, fontSize: 18)),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Info
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.blue.shade400.withOpacity(0.06),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Row(
                children: [
                  Icon(Icons.info_outline, color: Colors.blue.shade400, size: 16),
                  const SizedBox(width: 8),
                  Expanded(child: Text(
                    'Uses USSD *99# — works without internet',
                    style: TextStyle(color: Colors.blue.shade300, fontSize: 11),
                  )),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
