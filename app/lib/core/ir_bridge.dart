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

  /// Every row the store holds, in store order.
  static Future<List<Map<String, String>>> all() async {
    final n = await count();
    final out = <Map<String, String>>[];
    for (var i = 0; i < n; i++) {
      out.add(await row(i));
    }
    return out;
  }

  static Future<Map<String, String>> row(int i) async {
    final m = await _ch.invokeMethod<Map>('irRow', {'i': i});
    return {for (final e in (m ?? {}).entries) '${e.key}': '${e.value}'};
  }

  /// Names the row the tunnel dials. The VPN service looks the id up in the
  /// store at dial time, so a sync between the tap and the dial is safe.
  static Future<void> pick(String id) async =>
      await _ch.invokeMethod<void>('irPick', {'id': id});

  /// The row named before; "" when none.
  static Future<String> picked() async =>
      await _ch.invokeMethod<String>('irPicked') ?? '';
}
