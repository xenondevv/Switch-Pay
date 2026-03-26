import 'package:flutter/material.dart';
import '../services/payment_history.dart';
import 'transaction_detail_screen.dart';

class TransactionHistoryScreen extends StatefulWidget {
  const TransactionHistoryScreen({Key? key}) : super(key: key);

  @override
  State<TransactionHistoryScreen> createState() => _TransactionHistoryScreenState();
}

class _TransactionHistoryScreenState extends State<TransactionHistoryScreen> {
  List<PaymentRecord> _payments = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  Future<void> _loadAll() async {
    final records = await PaymentHistoryService.getRecords(limit: 100);
    if (mounted) setState(() { _payments = records; _loading = false; });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        surfaceTintColor: Colors.transparent,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios_new_rounded, color: Colors.white, size: 20),
          onPressed: () => Navigator.pop(context),
        ),
        title: const Text('Transaction History', style: TextStyle(
          color: Colors.white, fontSize: 18, fontWeight: FontWeight.w700)),
        centerTitle: true,
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator(color: Colors.white70, strokeWidth: 2))
          : _payments.isEmpty
              ? Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.receipt_long_rounded, color: Colors.grey.shade700, size: 56),
                      const SizedBox(height: 14),
                      Text('No transactions yet', style: TextStyle(
                        color: Colors.grey.shade500, fontSize: 16, fontWeight: FontWeight.w600)),
                      const SizedBox(height: 6),
                      Text('Your payment history will appear here', style: TextStyle(
                        color: Colors.grey.shade700, fontSize: 13)),
                    ],
                  ),
                )
              : ListView.builder(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                  itemCount: _payments.length,
                  itemBuilder: (context, index) {
                    final record = _payments[index];
                    final time = record.timestamp;
                    final timeStr = '${time.day}/${time.month}/${time.year}  ${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
                    return GestureDetector(
                      onTap: () => Navigator.push(context, MaterialPageRoute(
                        builder: (_) => TransactionDetailScreen(record: record))),
                      child: Padding(
                        padding: const EdgeInsets.only(bottom: 8),
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                          decoration: BoxDecoration(
                            color: const Color(0xFF111111),
                            borderRadius: BorderRadius.circular(14),
                            border: Border.all(color: Colors.grey.shade800),
                          ),
                          child: Row(
                            children: [
                              Container(
                                width: 36, height: 36,
                                decoration: BoxDecoration(
                                  color: (record.success ? Colors.green : Colors.red).withOpacity(0.1),
                                  borderRadius: BorderRadius.circular(10)),
                                child: Icon(
                                  record.success ? Icons.check_rounded : Icons.close_rounded,
                                  color: record.success ? Colors.green.shade400 : Colors.red.shade400,
                                  size: 18),
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      record.recipientName ?? record.target,
                                      style: const TextStyle(
                                        color: Colors.white, fontSize: 14, fontWeight: FontWeight.w600),
                                      maxLines: 1, overflow: TextOverflow.ellipsis,
                                    ),
                                    const SizedBox(height: 2),
                                    Text(timeStr, style: TextStyle(
                                      color: Colors.grey.shade600, fontSize: 11)),
                                  ],
                                ),
                              ),
                              Text(
                                '₹${record.amount}',
                                style: TextStyle(
                                  color: record.success ? Colors.white : Colors.grey.shade500,
                                  fontSize: 16, fontWeight: FontWeight.w700),
                              ),
                            ],
                          ),
                        ),
                      ),
                    );
                  },
                ),
    );
  }
}
