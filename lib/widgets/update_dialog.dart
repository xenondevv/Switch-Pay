import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import '../services/update_service.dart';

class UpdateDialog extends StatelessWidget {
  final AppUpdate update;

  const UpdateDialog({Key? key, required this.update}) : super(key: key);

  static Future<void> show(BuildContext context, AppUpdate update) {
    return showDialog(
      context: context,
      barrierDismissible: true,
      builder: (_) => UpdateDialog(update: update),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: const Color(0xFF111111),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(20),
        side: BorderSide(color: Colors.grey.shade800),
      ),
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 56, height: 56,
              decoration: BoxDecoration(
                color: Colors.white.withOpacity(0.08),
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.system_update_rounded,
                  color: Colors.white, size: 28),
            ),
            const SizedBox(height: 16),
            const Text('Update Available', style: TextStyle(
              color: Colors.white, fontSize: 20, fontWeight: FontWeight.w700)),
            const SizedBox(height: 6),
            Text('v${update.version}', style: TextStyle(
              color: Colors.grey.shade500, fontSize: 13)),
            const SizedBox(height: 16),

            // Release notes
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: Colors.black,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.grey.shade800),
              ),
              constraints: const BoxConstraints(maxHeight: 150),
              child: SingleChildScrollView(
                child: Text(update.releaseNotes, style: TextStyle(
                  color: Colors.grey.shade400, fontSize: 13, height: 1.5)),
              ),
            ),
            const SizedBox(height: 20),

            // Update button
            SizedBox(
              width: double.infinity, height: 48,
              child: ElevatedButton(
                onPressed: () async {
                  final uri = Uri.parse(update.downloadUrl);
                  await launchUrl(uri, mode: LaunchMode.externalApplication);
                },
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.white,
                  foregroundColor: Colors.black,
                  shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14)),
                  elevation: 0,
                ),
                child: const Text('Update Now', style: TextStyle(
                  fontWeight: FontWeight.w700, fontSize: 16)),
              ),
            ),
            const SizedBox(height: 10),

            // Later button
            GestureDetector(
              onTap: () => Navigator.pop(context),
              child: Text('Later', style: TextStyle(
                color: Colors.grey.shade500, fontSize: 14, fontWeight: FontWeight.w500)),
            ),
          ],
        ),
      ),
    );
  }
}
