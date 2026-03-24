import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import '../models/payment_request.dart';

class ScannerScreen extends StatefulWidget {
  const ScannerScreen({Key? key}) : super(key: key);

  @override
  State<ScannerScreen> createState() => _ScannerScreenState();
}

class _ScannerScreenState extends State<ScannerScreen> {
  final MobileScannerController _scannerController = MobileScannerController(
    detectionSpeed: DetectionSpeed.normal,
    facing: CameraFacing.back,
  );
  final TextEditingController _manualInput = TextEditingController();
  PaymentRequest? _scannedRequest;
  bool _hasScanned = false;
  bool _showManual = false;

  @override
  void dispose() {
    _scannerController.dispose();
    _manualInput.dispose();
    super.dispose();
  }

  void _onDetect(BarcodeCapture capture) {
    if (_hasScanned) return;
    final barcode = capture.barcodes.firstOrNull;
    if (barcode == null || barcode.rawValue == null) return;

    final raw = barcode.rawValue!;
    if (!raw.toLowerCase().startsWith('upi://')) return;

    setState(() {
      _hasScanned = true;
    });
    _parseAndShow(raw);
  }

  void _parseAndShow(String qrData) {
    try {
      final req = PaymentRequest.fromQrData(qrData);
      if (req.target.isNotEmpty) {
        setState(() => _scannedRequest = req);
      } else {
        _showError('No UPI ID found in QR code');
        setState(() => _hasScanned = false);
      }
    } catch (_) {
      _showError('Invalid UPI QR code');
      setState(() => _hasScanned = false);
    }
  }

  void _parseManual() {
    final input = _manualInput.text.trim();
    if (input.isEmpty) return;
    _parseAndShow(input);
  }

  void _proceedToPayment() {
    if (_scannedRequest == null) return;
    Navigator.pop(context, _scannedRequest);
  }

  void _rescan() {
    setState(() {
      _hasScanned = false;
      _scannedRequest = null;
    });
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
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: const Text('Scan QR Code'),
        backgroundColor: const Color(0xFF111111),
        foregroundColor: Colors.white,
        elevation: 0,
        actions: [
          IconButton(
            icon: Icon(_showManual ? Icons.camera_alt : Icons.edit,
              color: Colors.grey.shade400),
            onPressed: () => setState(() => _showManual = !_showManual),
            tooltip: _showManual ? 'Use Camera' : 'Paste QR Data',
          ),
        ],
      ),
      body: Column(
        children: [
          // Camera / Manual input area
          if (!_showManual)
            SizedBox(
              height: 300,
              child: Stack(
                children: [
                  ClipRRect(
                    borderRadius: const BorderRadius.vertical(bottom: Radius.circular(20)),
                    child: MobileScanner(
                      controller: _scannerController,
                      onDetect: _onDetect,
                    ),
                  ),
                  // Overlay frame — white border
                  Center(
                    child: Container(
                      width: 220, height: 220,
                      decoration: BoxDecoration(
                        border: Border.all(color: Colors.white, width: 2),
                        borderRadius: BorderRadius.circular(16),
                      ),
                    ),
                  ),
                  // Torch toggle
                  Positioned(
                    bottom: 16, right: 16,
                    child: GestureDetector(
                      onTap: () => _scannerController.toggleTorch(),
                      child: Container(
                        padding: const EdgeInsets.all(10),
                        decoration: BoxDecoration(
                          color: Colors.black54,
                          borderRadius: BorderRadius.circular(12)),
                        child: const Icon(Icons.flash_on, color: Colors.white, size: 22),
                      ),
                    ),
                  ),
                  // Scanning label
                  Positioned(
                    bottom: 16, left: 0, right: 0,
                    child: Center(
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                        decoration: BoxDecoration(
                          color: Colors.black54,
                          borderRadius: BorderRadius.circular(20)),
                        child: Text(
                          _hasScanned ? '✅ QR Detected' : 'Point at UPI QR code',
                          style: TextStyle(
                            color: _hasScanned ? Colors.white : Colors.grey.shade300,
                            fontSize: 13, fontWeight: FontWeight.w500),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            )
          else
            Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text('Paste UPI QR Data', style: TextStyle(
                    color: Colors.grey.shade300, fontSize: 14, fontWeight: FontWeight.w600)),
                  const SizedBox(height: 8),
                  TextField(
                    controller: _manualInput,
                    style: const TextStyle(color: Colors.white, fontSize: 14),
                    maxLines: 3,
                    decoration: InputDecoration(
                      hintText: 'upi://pay?pa=merchant@upi&pn=Shop&am=100',
                      hintStyle: TextStyle(color: Colors.grey.shade700, fontSize: 12),
                      filled: true,
                      fillColor: const Color(0xFF111111),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: BorderSide(color: Colors.grey.shade800)),
                      enabledBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: BorderSide(color: Colors.grey.shade800)),
                      focusedBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: const BorderSide(color: Colors.white)),
                    ),
                  ),
                  const SizedBox(height: 12),
                  SizedBox(
                    height: 44,
                    child: ElevatedButton(
                      onPressed: _parseManual,
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.white,
                        foregroundColor: Colors.black,
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                      ),
                      child: const Text('Parse QR', style: TextStyle(fontWeight: FontWeight.w600)),
                    ),
                  ),
                ],
              ),
            ),

          // Parsed result
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(20),
              child: _scannedRequest != null
                  ? _buildParsedCard()
                  : _buildHintCard(),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildParsedCard() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: const Color(0xFF111111),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: Colors.white.withOpacity(0.3)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.check_circle, color: Colors.white, size: 22),
                  const SizedBox(width: 8),
                  const Text('QR Parsed Successfully', style: TextStyle(
                    color: Colors.white, fontWeight: FontWeight.w700, fontSize: 16)),
                ],
              ),
              const SizedBox(height: 16),
              _infoRow('UPI ID', _scannedRequest!.target),
              if (_scannedRequest!.recipientName != null)
                _infoRow('Name', _scannedRequest!.recipientName!),
              _infoRow('Amount', _scannedRequest!.amount.isNotEmpty
                  ? '₹${_scannedRequest!.amount}' : 'Not specified'),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              child: SizedBox(
                height: 50,
                child: OutlinedButton(
                  onPressed: _rescan,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: Colors.grey.shade400,
                    side: BorderSide(color: Colors.grey.shade700),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  ),
                  child: const Text('Scan Again', style: TextStyle(fontWeight: FontWeight.w600)),
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              flex: 2,
              child: SizedBox(
                height: 50,
                child: ElevatedButton(
                  onPressed: _proceedToPayment,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.white,
                    foregroundColor: Colors.black,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  ),
                  child: const Text('Proceed to Pay', style: TextStyle(
                    fontWeight: FontWeight.w700, fontSize: 16)),
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildHintCard() {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: const Color(0xFF111111),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.grey.shade800),
      ),
      child: Column(
        children: [
          Icon(Icons.qr_code_2, size: 48, color: Colors.grey.shade600),
          const SizedBox(height: 12),
          Text('Scan a UPI QR Code', style: TextStyle(
            color: Colors.grey.shade400, fontSize: 14, fontWeight: FontWeight.w500)),
          const SizedBox(height: 6),
          Text('Supported format: upi://pay?pa=...', style: TextStyle(
            color: Colors.grey.shade600, fontSize: 12)),
        ],
      ),
    );
  }

  Widget _infoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: TextStyle(color: Colors.grey.shade500, fontSize: 14)),
          Flexible(child: Text(value, style: const TextStyle(
            color: Colors.white, fontSize: 14, fontWeight: FontWeight.w500),
            textAlign: TextAlign.end)),
        ],
      ),
    );
  }
}
