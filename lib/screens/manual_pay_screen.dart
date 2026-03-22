import 'package:flutter/material.dart';
import 'result_screen.dart';

class ManualPayScreen extends StatefulWidget {
  final bool forceIvr;

  const ManualPayScreen({Key? key, this.forceIvr = false}) : super(key: key);

  @override
  State<ManualPayScreen> createState() => _ManualPayScreenState();
}

class _ManualPayScreenState extends State<ManualPayScreen> {
  final TextEditingController _targetController = TextEditingController();
  final TextEditingController _amountController = TextEditingController();
  late String _selectedMode;

  final List<String> _presetAmounts = ['100', '200', '500', '1000', '2000'];

  @override
  void initState() {
    super.initState();
    _selectedMode = widget.forceIvr ? 'IVR' : 'AUTO';
  }

  void _sendPayment() {
    final target = _targetController.text.trim();
    final amount = _amountController.text.trim();

    if (target.isEmpty || amount.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: const Text('Enter phone/UPI ID and amount'),
          backgroundColor: Colors.red.shade800,
          behavior: SnackBarBehavior.floating,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        ),
      );
      return;
    }

    // AUTO = tries USSD first, auto-falls back to IVR
    // IVR = goes directly to IVR
    final mode = _selectedMode == 'AUTO' ? 'USSD' : _selectedMode;

    Navigator.push(context, MaterialPageRoute(
      builder: (_) => ResultScreen(target: target, amount: amount, mode: mode),
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
            // Info banner
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.blue.shade400.withOpacity(0.08),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.blue.shade400.withOpacity(0.2)),
              ),
              child: Row(
                children: [
                  Icon(Icons.info_outline, color: Colors.blue.shade400, size: 18),
                  const SizedBox(width: 8),
                  Expanded(child: Text(
                    _selectedMode == 'IVR'
                      ? 'IVR will auto-dial and fill your details via DTMF tones.'
                      : 'Tries USSD in-app first. Auto-switches to IVR if USSD fails.',
                    style: TextStyle(color: Colors.blue.shade300, fontSize: 12),
                  )),
                ],
              ),
            ),
            const SizedBox(height: 24),

            // Recipient
            Text('Recipient', style: TextStyle(
              color: Colors.grey.shade300, fontSize: 14, fontWeight: FontWeight.w600)),
            const SizedBox(height: 8),
            TextField(
              controller: _targetController,
              style: const TextStyle(color: Colors.white, fontSize: 16),
              keyboardType: TextInputType.phone,
              decoration: _inputDecoration('Phone number or UPI ID', Icons.person_outline),
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
            const SizedBox(height: 28),

            // Mode selector
            Text('Payment Mode', style: TextStyle(
              color: Colors.grey.shade300, fontSize: 14, fontWeight: FontWeight.w600)),
            const SizedBox(height: 8),
            Container(
              decoration: BoxDecoration(
                color: const Color(0xFF161B22),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: const Color(0xFF30363D)),
              ),
              child: Row(
                children: [
                  _modeChip('AUTO', Icons.auto_fix_high, 'Auto'),
                  _modeChip('IVR', Icons.phone_in_talk, 'IVR'),
                ],
              ),
            ),
            const SizedBox(height: 28),

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
                child: const Text('Send Payment', style: TextStyle(
                    fontWeight: FontWeight.w700, fontSize: 18)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _modeChip(String mode, IconData icon, String label) {
    final isSelected = _selectedMode == mode;
    final color = mode == 'IVR' ? Colors.orange.shade400 : Colors.green.shade400;
    return Expanded(
      child: GestureDetector(
        onTap: () => setState(() => _selectedMode = mode),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 14),
          decoration: BoxDecoration(
            color: isSelected ? color.withOpacity(0.15) : Colors.transparent,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Column(
            children: [
              Icon(icon, color: isSelected ? color : Colors.grey.shade500, size: 22),
              const SizedBox(height: 4),
              Text(label, style: TextStyle(
                color: isSelected ? color : Colors.grey.shade500,
                fontSize: 12, fontWeight: FontWeight.w600)),
            ],
          ),
        ),
      ),
    );
  }

  InputDecoration _inputDecoration(String hint, IconData icon) {
    return InputDecoration(
      hintText: hint,
      hintStyle: TextStyle(color: Colors.grey.shade700),
      prefixIcon: Icon(icon, color: Colors.grey.shade500),
      filled: true,
      fillColor: const Color(0xFF161B22),
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(12),
        borderSide: const BorderSide(color: Color(0xFF30363D))),
      enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12),
        borderSide: const BorderSide(color: Color(0xFF30363D))),
      focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide(color: Colors.green.shade400)),
    );
  }
}
