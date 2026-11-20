import 'package:flutter/services.dart';

/// The row store our C keeps, through the Android host. Read-only: the count and the four
/// fields the native side answers. Nothing here starts or stops anything yet.
class IrRowsBridge {
  static const _ch = MethodChannel('app/android');

  static Future<int> count() async =>
      await _ch.invokeMethod<int>('irRows') ?? 0;

  /// One refresh through the gate; returns its outcome code.
  static Future<int> refresh() async =>
      await _ch.invokeMethod<int>('irRefresh') ?? -1;

  static Future<Map<String, String>> row(int i) async {
    final m = await _ch.invokeMethod<Map>('irRow', {'i': i});
    return {for (final e in (m ?? {}).entries) '${e.key}': '${e.value}'};
  }
}
