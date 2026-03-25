import 'dart:async';
import 'package:flutter/material.dart';
import '../services/platform_channel.dart';
import '../services/payment_history.dart';
import '../services/favorites_service.dart';

class ResultScreen extends StatefulWidget {
  final String target;
  final String amount;
  final String? recipientName;

  const ResultScreen({
    Key? key,
    required this.target,
    required this.amount,
    this.recipientName,
  }) : super(key: key);

  @override
  State<ResultScreen> createState() => _ResultScreenState();
}

class _ResultScreenState extends State<ResultScreen> with TickerProviderStateMixin {
  final List<PaymentStep> _steps = [];
  StreamSubscription? _sub;
  bool _isComplete = false;
  bool _hasError = false;
  bool _isSuccess = false;
  bool _showPinPad = false;
  String _finalMessage = '';
  String _currentPin = '';
  bool _savedToFavorites = false;
  late AnimationController _pulseController;

  @override
  void initState() {
    super.initState();

    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1200),
    )..repeat(reverse: true);

    _sub = NativeBridge.statusStream.listen((step) {
      if (!mounted) return;
      setState(() {
        _steps.add(step);
        if (step.status == 'enter_pin') {
          // PIN is handled by the native overlay — do NOT show Flutter PIN pad
        }
        if (step.isFinal) {
          _isComplete = true;
          _hasError = step.status.contains('error') || step.status.contains('fail');
          _isSuccess = step.status.contains('success');
          _finalMessage = step.message;
          _showPinPad = false;
          _pulseController.stop();
          // Save payment record
          PaymentHistoryService.addRecord(PaymentRecord(
            target: widget.target,
            amount: widget.amount,
            recipientName: widget.recipientName,
            timestamp: DateTime.now(),
            success: _isSuccess,
          ));
        }
      });
    });

    _triggerPayment();
  }

  void _triggerPayment() async {
    await NativeBridge.triggerOfflinePayment(
      target: widget.target,
      amount: widget.amount,
    );
  }

  @override
  void dispose() {
    _sub?.cancel();
    _pulseController.dispose();
    super.dispose();
  }

  void _onPinKeyPress(String key) {
    setState(() {
      if (key == '⌫') {
        if (_currentPin.isNotEmpty) _currentPin = _currentPin.substring(0, _currentPin.length - 1);
      } else if (key == '✓') {
        if (_currentPin.length >= 4) {
          NativeBridge.sendUpiPin(_currentPin);
          _showPinPad = false;
          _steps.add(PaymentStep(status: 'pin_sent', message: '🔒 UPI PIN submitted — processing...', isFinal: false));
        }
      } else {
        if (_currentPin.length < 6) _currentPin += key;
      }
    });
  }

  IconData _iconForStatus(String status) {
    if (status.contains('error') || status.contains('fail')) return Icons.error_rounded;
    if (status.contains('success')) return Icons.check_circle_rounded;
    if (status.contains('pin')) return Icons.lock_rounded;
    if (status.contains('dialing')) return Icons.cell_tower_rounded;
    if (status.contains('menu') || status.contains('bank')) return Icons.list_alt_rounded;
    if (status.contains('select') || status.contains('method')) return Icons.touch_app_rounded;
    if (status.contains('number') || status.contains('recipient')) return Icons.person_rounded;
    if (status.contains('amount')) return Icons.currency_rupee_rounded;
    if (status.contains('remark')) return Icons.note_rounded;
    return Icons.radio_button_checked_rounded;
  }

  Color _colorForStatus(String status) {
    if (status.contains('error') || status.contains('fail')) return Colors.red.shade400;
    if (status.contains('success')) return Colors.green.shade400;
    if (status.contains('pin')) return Colors.amber.shade400;
    return Colors.white70;
  }

  @override
  Widget build(BuildContext context) {
    // Show full success/failure screen when complete
    if (_isComplete) {
      return Scaffold(
        backgroundColor: Colors.black,
        body: SafeArea(
          child: _isSuccess ? _buildSuccessScreen() : _buildFailureScreen(),
        ),
      );
    }

    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Column(
          children: [
            const SizedBox(height: 16),
            _buildHeader(),
            const SizedBox(height: 16),
            Expanded(
              child: _buildStepList(),
            ),
            const SizedBox(height: 16),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader() {
    Color headerColor = Colors.white70;
    IconData headerIcon = Icons.cell_tower_rounded;
    String headerText = 'Processing via USSD';

    if (_showPinPad) {
      headerColor = Colors.amber.shade400;
      headerIcon = Icons.lock_rounded;
      headerText = 'Enter UPI PIN';
    } else if (_isComplete) {
      if (_isSuccess) {
        headerColor = Colors.green.shade400;
        headerIcon = Icons.check_rounded;
        headerText = 'Payment Successful';
      } else if (_hasError) {
        headerColor = Colors.red.shade400;
        headerIcon = Icons.close_rounded;
        headerText = 'Payment Failed';
      }
    }

    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 20),
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft, end: Alignment.bottomRight,
          colors: [headerColor.withOpacity(0.1), const Color(0xFF111111)],
        ),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: headerColor.withOpacity(0.25)),
      ),
      child: Column(
        children: [
          AnimatedBuilder(
            animation: _pulseController,
            builder: (context, child) {
              final scale = _isComplete ? 1.0
                  : _showPinPad ? 1.0
                  : 0.9 + _pulseController.value * 0.1;
              return Transform.scale(
                scale: scale,
                child: Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: headerColor.withOpacity(0.12),
                  ),
                  child: Icon(headerIcon, color: headerColor, size: 36),
                ),
              );
            },
          ),
          const SizedBox(height: 12),
          Text(headerText, style: TextStyle(
            color: headerColor, fontSize: 18, fontWeight: FontWeight.w700)),
          const SizedBox(height: 8),
          Text('₹${widget.amount}', style: const TextStyle(
            color: Colors.white, fontSize: 32, fontWeight: FontWeight.w800, letterSpacing: -1)),
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              'To: ${widget.target}',
              style: TextStyle(color: Colors.grey.shade400, fontSize: 13),
            ),
          ),
          const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
            decoration: BoxDecoration(
              color: Colors.white.withOpacity(0.08),
              borderRadius: BorderRadius.circular(12),
            ),
            child: const Text(
              '📱 USSD *99#',
              style: TextStyle(
                color: Colors.white70,
                fontSize: 11, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStepList() {
    if (_steps.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(width: 32, height: 32,
              child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white70)),
            const SizedBox(height: 12),
            Text('Initiating USSD...', style: TextStyle(color: Colors.grey.shade500)),
          ],
        ),
      );
    }

    return ListView.builder(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      itemCount: _steps.length + (_isComplete ? 0 : 1),
      itemBuilder: (context, index) {
        if (index == _steps.length) {
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: Row(
              children: [
                const SizedBox(width: 18, height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white70)),
                const SizedBox(width: 14),
                Text('Waiting for USSD response...', style: TextStyle(
                  color: Colors.grey.shade500, fontSize: 13)),
              ],
            ),
          );
        }

        final step = _steps[index];
        final isLast = index == _steps.length - 1;
        final icon = _iconForStatus(step.status);
        final color = _colorForStatus(step.status);

        return Padding(
          padding: const EdgeInsets.only(bottom: 2),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Column(
                children: [
                  Container(
                    padding: const EdgeInsets.all(5),
                    decoration: BoxDecoration(
                      color: color.withOpacity(0.12), shape: BoxShape.circle),
                    child: Icon(icon, color: color, size: 14),
                  ),
                  if (!isLast || !_isComplete)
                    Container(width: 2, height: 24, color: Colors.grey.shade800),
                ],
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Container(
                  margin: const EdgeInsets.only(bottom: 6),
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: const Color(0xFF111111),
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(
                      color: isLast && !_isComplete
                          ? color.withOpacity(0.35) : Colors.grey.shade800),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(step.message, style: TextStyle(
                        color: isLast ? Colors.white : Colors.grey.shade400,
                        fontSize: 12, fontWeight: isLast ? FontWeight.w500 : FontWeight.normal,
                        height: 1.4)),
                      const SizedBox(height: 3),
                      Text(_formatTime(step.timestamp), style: TextStyle(
                        color: Colors.grey.shade700, fontSize: 10)),
                    ],
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildPinPad() {
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      child: Column(
        children: [
          const SizedBox(height: 8),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(vertical: 20, horizontal: 24),
            decoration: BoxDecoration(
              color: const Color(0xFF111111),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Colors.amber.shade400.withOpacity(0.4)),
            ),
            child: Column(
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.lock_rounded, color: Colors.amber.shade400, size: 18),
                    const SizedBox(width: 8),
                    Text('Enter your UPI PIN', style: TextStyle(
                      color: Colors.amber.shade400, fontSize: 14, fontWeight: FontWeight.w600)),
                  ],
                ),
                const SizedBox(height: 16),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: List.generate(6, (i) => Container(
                    width: 40, height: 40,
                    margin: const EdgeInsets.symmetric(horizontal: 4),
                    decoration: BoxDecoration(
                      color: i < _currentPin.length
                          ? Colors.amber.shade400.withOpacity(0.2)
                          : Colors.black,
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(
                        color: i < _currentPin.length
                            ? Colors.amber.shade400 : Colors.grey.shade800,
                        width: 1.5),
                    ),
                    child: Center(
                      child: i < _currentPin.length
                          ? Container(width: 12, height: 12,
                              decoration: BoxDecoration(
                                color: Colors.amber.shade400,
                                shape: BoxShape.circle))
                          : null,
                    ),
                  )),
                ),
                const SizedBox(height: 8),
                Text('4-6 digits', style: TextStyle(
                  color: Colors.grey.shade600, fontSize: 11)),
              ],
            ),
          ),
          const SizedBox(height: 16),

          ...[
            ['1', '2', '3'],
            ['4', '5', '6'],
            ['7', '8', '9'],
            ['⌫', '0', '✓'],
          ].map((row) => Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Row(
              children: row.map((key) => Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: SizedBox(
                    height: 56,
                    child: ElevatedButton(
                      onPressed: () => _onPinKeyPress(key),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: key == '✓'
                            ? (_currentPin.length >= 4 ? Colors.white.withOpacity(0.15) : const Color(0xFF111111))
                            : key == '⌫'
                                ? Colors.red.shade400.withOpacity(0.1)
                                : const Color(0xFF111111),
                        foregroundColor: key == '✓'
                            ? (_currentPin.length >= 4 ? Colors.white : Colors.grey.shade700)
                            : key == '⌫'
                                ? Colors.red.shade400
                                : Colors.white,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                          side: BorderSide(color: Colors.grey.shade800),
                        ),
                        elevation: 0,
                      ),
                      child: Text(key, style: TextStyle(
                        fontSize: key == '✓' || key == '⌫' ? 22 : 20,
                        fontWeight: FontWeight.w600)),
                    ),
                  ),
                ),
              )).toList(),
            ),
          )),

          const SizedBox(height: 8),
          Text('PIN is entered securely via USSD', style: TextStyle(
            color: Colors.grey.shade600, fontSize: 11)),
        ],
      ),
    );
  }

  Widget _buildSuccessScreen() {
    final now = DateTime.now();
    final timeStr = '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}';
    final dateStr = '${now.day}/${now.month}/${now.year}';

    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Column(
        children: [
          const SizedBox(height: 48),
          // Big checkmark
          Container(
            width: 100, height: 100,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              gradient: LinearGradient(
                begin: Alignment.topLeft, end: Alignment.bottomRight,
                colors: [Colors.green.shade400, Colors.green.shade700]),
              boxShadow: [
                BoxShadow(color: Colors.green.shade400.withOpacity(0.3),
                  blurRadius: 30, spreadRadius: 5),
              ],
            ),
            child: const Icon(Icons.check_rounded, color: Colors.white, size: 56),
          ),
          const SizedBox(height: 24),
          Text('Payment Successful', style: TextStyle(
            color: Colors.green.shade400, fontSize: 22, fontWeight: FontWeight.w800)),
          const SizedBox(height: 8),
          Text('₹${widget.amount}', style: const TextStyle(
            color: Colors.white, fontSize: 48, fontWeight: FontWeight.w800, letterSpacing: -2)),
          const SizedBox(height: 32),

          // Payment details card
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              color: const Color(0xFF111111),
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: Colors.grey.shade800),
            ),
            child: Column(
              children: [
                _detailRow(Icons.person_rounded, 'To',
                  widget.recipientName != null && widget.recipientName!.isNotEmpty
                    ? '${widget.recipientName}\n${widget.target}'
                    : widget.target),
                _divider(),
                _detailRow(Icons.currency_rupee_rounded, 'Amount', '₹${widget.amount}'),
                _divider(),
                _detailRow(Icons.phone_android_rounded, 'Method', 'USSD *99#'),
                _divider(),
                _detailRow(Icons.access_time_rounded, 'Time', '$timeStr • $dateStr'),

              ],
            ),
          ),
          const SizedBox(height: 24),

          // via USSD badge
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            decoration: BoxDecoration(
              color: Colors.white.withOpacity(0.06),
              borderRadius: BorderRadius.circular(20),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.wifi_off_rounded, color: Colors.white70, size: 14),
                const SizedBox(width: 6),
                const Text('Completed without internet', style: TextStyle(
                  color: Colors.white70, fontSize: 12, fontWeight: FontWeight.w600)),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // Save to Favorites
          SizedBox(
            width: double.infinity, height: 48,
            child: OutlinedButton(
              onPressed: _savedToFavorites ? null : () async {
                await FavoritesService.add(FavoriteContact(
                  name: widget.recipientName ?? widget.target.split('@').first,
                  upiId: widget.target,
                  lastPaid: DateTime.now(),
                ));
                if (mounted) setState(() => _savedToFavorites = true);
              },
              style: OutlinedButton.styleFrom(
                foregroundColor: Colors.white70,
                side: BorderSide(color: Colors.grey.shade800),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(_savedToFavorites ? Icons.check_rounded : Icons.favorite_border_rounded, size: 18),
                  const SizedBox(width: 8),
                  Text(_savedToFavorites ? 'Saved to Favorites' : 'Save to Favorites',
                    style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),

          // Done button
          SizedBox(
            width: double.infinity, height: 56,
            child: ElevatedButton(
              onPressed: () => Navigator.of(context).popUntil((route) => route.isFirst),
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.white,
                foregroundColor: Colors.black,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                elevation: 0,
              ),
              child: const Text('Done', style: TextStyle(
                fontWeight: FontWeight.w800, fontSize: 18)),
            ),
          ),
          const SizedBox(height: 32),
        ],
      ),
    );
  }

  Widget _buildFailureScreen() {
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Column(
        children: [
          const SizedBox(height: 48),
          Container(
            width: 100, height: 100,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: Colors.red.shade400,
              boxShadow: [
                BoxShadow(color: Colors.red.shade400.withOpacity(0.3),
                  blurRadius: 30, spreadRadius: 5),
              ],
            ),
            child: const Icon(Icons.close_rounded, color: Colors.white, size: 56),
          ),
          const SizedBox(height: 24),
          Text('Payment Failed', style: TextStyle(
            color: Colors.red.shade400, fontSize: 22, fontWeight: FontWeight.w800)),
          const SizedBox(height: 12),
          Text('₹${widget.amount}', style: const TextStyle(
            color: Colors.white, fontSize: 36, fontWeight: FontWeight.w800)),
          const SizedBox(height: 8),
          Text('To: ${widget.target}', style: TextStyle(
            color: Colors.grey.shade400, fontSize: 14)),
          const SizedBox(height: 24),
          if (_finalMessage.isNotEmpty)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.red.shade400.withOpacity(0.06),
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: Colors.red.shade400.withOpacity(0.25)),
              ),
              child: Text(_finalMessage.replaceAll('❌ ', ''),
                style: TextStyle(color: Colors.red.shade300, fontSize: 13, height: 1.4),
                textAlign: TextAlign.center),
            ),
          const SizedBox(height: 32),
          SizedBox(
            width: double.infinity, height: 56,
            child: ElevatedButton(
              onPressed: () => Navigator.of(context).popUntil((route) => route.isFirst),
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.red.shade400,
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                elevation: 0,
              ),
              child: const Text('Back to Home', style: TextStyle(
                fontWeight: FontWeight.w700, fontSize: 18)),
            ),
          ),
          const SizedBox(height: 32),
        ],
      ),
    );
  }

  Widget _detailRow(IconData icon, String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: Colors.grey.shade800.withOpacity(0.4),
              borderRadius: BorderRadius.circular(10)),
            child: Icon(icon, color: Colors.grey.shade400, size: 18),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: TextStyle(
                  color: Colors.grey.shade600, fontSize: 12, fontWeight: FontWeight.w500)),
                const SizedBox(height: 3),
                Text(value, style: const TextStyle(
                  color: Colors.white, fontSize: 14, fontWeight: FontWeight.w600, height: 1.4)),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _divider() {
    return Divider(color: Colors.grey.shade800, height: 1, thickness: 0.5);
  }

  String _formatTime(DateTime time) {
    return '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}:${time.second.toString().padLeft(2, '0')}';
  }
}
