import 'package:flutter/material.dart';
import 'screens/splash_screen.dart';
import 'services/platform_channel.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  NativeBridge.init();
  runApp(const SwitchPayApp());
}

class SwitchPayApp extends StatelessWidget {
  const SwitchPayApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Switch Pay',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: Colors.black,
        primarySwatch: Colors.grey,
        useMaterial3: true,
        fontFamily: 'Roboto',
        colorScheme: const ColorScheme.dark(
          primary: Colors.white,
          secondary: Colors.white70,
          surface: Color(0xFF111111),
        ),
      ),
      home: const SplashScreen(),
    );
  }
}
