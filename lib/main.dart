import 'package:flutter/material.dart';
import 'screens/home_screen.dart';
import 'services/platform_channel.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  NativeBridge.init();
  runApp(const OfflinePaymentApp());
}

class OfflinePaymentApp extends StatelessWidget {
  const OfflinePaymentApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Offline Payment Assistant',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF0D1117),
        primarySwatch: Colors.green,
        useMaterial3: true,
        fontFamily: 'Roboto',
      ),
      home: const HomeScreen(),
    );
  }
}
