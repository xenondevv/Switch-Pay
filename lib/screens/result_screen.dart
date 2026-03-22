import 'dart:async';
import 'package:flutter/material.dart';
import '../services/platform_channel.dart';

class ResultScreen extends StatefulWidget {
  final String target;
  final String amount;
  final String mode;

  const ResultScreen({
    Key? key,
    required this.target,
    required this.amount,
    required this.mode,
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
  bool _switchedToIvr = false;
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
        if (step.status.contains('ivr')) _switchedToIvr = true;
        if (step.isFinal) {
          _isComplete = true;
          _hasError = step.status.contains('error') || step.status.contains('fail');
          _isSuccess = step.status.contains('success');
          _pulseController.stop();
        }
      });
    });

    _triggerPayment();
  }

  void _triggerPayment() async {
    await NativeBridge.triggerOfflinePayment(
      target: widget.target,
      amount: widget.amount,
      mode: widget.mode,
    );
  }

  @override
  void dispose() {
    _sub?.cancel();
    _pulseController.dispose();
    super.dispose();
  }

  IconData _iconForStatus(String status) {
    if (status.contains('error') || status.contains('fail')) return Icons.error_rounded;
    if (status.contains('success')) return Icons.check_circle_rounded;
    if (status.contains('pin')) return Icons.lock_rounded;
    if (status.contains('connecting')) return Icons.cell_tower_rounded;
    if (status.contains('menu')) return Icons.list_alt_rounded;
    if (status.contains('selecting')) return Icons.touch_app_rounded;
    if (status.contains('number')) return Icons.person_rounded;
    if (status.contains('amount')) return Icons.currency_rupee_rounded;
    if (status.contains('ivr')) return Icons.phone_in_talk_rounded;
    if (status.contains('ussd_fail')) return Icons.warning_rounded;
    return Icons.radio_button_checked_rounded;
  }

  Color _colorForStatus(String status) {
    if (status.contains('error') || status.contains('fail')) return Colors.red.shade400;
    if (status.contains('success')) return Colors.green.shade400;
    if (status.contains('pin')) return Colors.amber.shade400;
    if (status.contains('ivr')) return Colors.orange.shade400;
    if (status.contains('ussd_fail')) return Colors.orange.shade400;
    if (status.contains('connecting')) return Colors.blue.shade400;
    return Colors.blue.shade400;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0D1117),
      body: SafeArea(
        child: Column(
          children: [
            const SizedBox(height: 16),
            _buildHeader(),
            const SizedBox(height: 20),
            Expanded(child: _buildStepList()),
            if (_isComplete) _buildBottomAction(),
            const SizedBox(height: 16),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader() {
    Color headerColor = Colors.blue.shade400;
    IconData headerIcon = Icons.cell_tower_rounded;
    String headerText = 'Processing...';

    if (_switchedToIvr && !_isComplete) {
      headerColor = Colors.orange.shade400;
      headerIcon = Icons.phone_in_talk_rounded;
      headerText = 'IVR Auto-Navigation';
    } else if (_isComplete) {
      if (_isSuccess) {
        headerColor = Colors.green.shade400;
        headerIcon = Icons.check_rounded;
        headerText = 'Complete';
      } else if (_hasError) {
        headerColor = Colors.red.shade400;
        headerIcon = Icons.close_rounded;
        headerText = 'Failed';
      } else {
        headerColor = Colors.amber.shade400;
        headerIcon = Icons.lock_rounded;
        headerText = 'PIN Required';
      }
    }

    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 20),
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft, end: Alignment.bottomRight,
          colors: [headerColor.withOpacity(0.15), const Color(0xFF161B22)],
        ),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: headerColor.withOpacity(0.3)),
      ),
      child: Column(
        children: [
          AnimatedBuilder(
            animation: _pulseController,
            builder: (context, child) {
              final scale = _isComplete ? 1.0 : 0.9 + _pulseController.value * 0.1;
              return Transform.scale(
                scale: scale,
                child: Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: headerColor.withOpacity(0.15),
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

          if (widget.amount.isNotEmpty && widget.amount != '0')
            Text('₹${widget.amount}', style: const TextStyle(
              color: Colors.white, fontSize: 32, fontWeight: FontWeight.w800, letterSpacing: -1)),

          if (widget.target.isNotEmpty && widget.target != 'BALANCE')
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text('To: ${widget.target}', style: TextStyle(
                color: Colors.grey.shade400, fontSize: 13)),
            ),

          const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
            decoration: BoxDecoration(
              color: _switchedToIvr
                  ? Colors.orange.shade400.withOpacity(0.1)
                  : Colors.green.shade400.withOpacity(0.1),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Text(
              _switchedToIvr ? '📞 IVR (Auto-DTMF)' : '📱 USSD (In-App)',
              style: TextStyle(
                color: _switchedToIvr ? Colors.orange.shade400 : Colors.green.shade400,
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
            SizedBox(width: 32, height: 32,
              child: CircularProgressIndicator(strokeWidth: 2, color: Colors.blue.shade400)),
            const SizedBox(height: 12),
            Text('Connecting...', style: TextStyle(color: Colors.grey.shade500)),
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
                SizedBox(width: 18, height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.blue.shade400)),
                const SizedBox(width: 14),
                Text('Waiting for response...', style: TextStyle(
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
                      color: color.withOpacity(0.15), shape: BoxShape.circle),
                    child: Icon(icon, color: color, size: 14),
                  ),
                  if (!isLast || !_isComplete)
                    Container(width: 2, height: 24, color: const Color(0xFF30363D)),
                ],
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Container(
                  margin: const EdgeInsets.only(bottom: 6),
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: const Color(0xFF161B22),
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(
                      color: isLast && !_isComplete
                          ? color.withOpacity(0.4) : const Color(0xFF30363D)),
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

  Widget _buildBottomAction() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      child: SizedBox(
        width: double.infinity, height: 50,
        child: ElevatedButton(
          onPressed: () => Navigator.pop(context),
          style: ElevatedButton.styleFrom(
            backgroundColor: _hasError ? Colors.red.shade400 : Colors.green.shade400,
            foregroundColor: Colors.black,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
            elevation: 0,
          ),
          child: Text(_hasError ? 'Back' : 'Done',
            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
        ),
      ),
    );
  }

  String _formatTime(DateTime time) {
    return '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}:${time.second.toString().padLeft(2, '0')}';
  }
}
