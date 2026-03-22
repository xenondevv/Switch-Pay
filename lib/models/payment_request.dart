class PaymentRequest {
  final String target;
  final String amount;
  final String? recipientName;

  PaymentRequest({
    required this.target,
    required this.amount,
    this.recipientName,
  });

  /// Parse a UPI QR code string like: upi://pay?pa=merchant@upi&pn=Shop&am=100
  factory PaymentRequest.fromQrData(String qrData) {
    final uri = Uri.parse(qrData);
    return PaymentRequest(
      target: uri.queryParameters['pa'] ?? '',
      amount: uri.queryParameters['am'] ?? '',
      recipientName: uri.queryParameters['pn'],
    );
  }

  bool get isValid => target.isNotEmpty && amount.isNotEmpty;
}
