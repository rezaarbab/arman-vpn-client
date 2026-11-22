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

  /// Every row the store holds, in store order, in one answer.
  static Future<List<Map<String, String>>> all() async {
    final list = await _ch.invokeListMethod<Object?>('irRowsAll') ?? const [];
    final out = <Map<String, String>>[];
    for (final e in list) {
      if (e is Map) {
        out.add(<String, String>{for (final x in e.entries) '${x.key}': '${x.value}'});
      }
    }
    return out;
  }

  /// Why the last refresh failed, as the gate words it; "" for none.
  static Future<String> why() async =>
      await _ch.invokeMethod<String>('irSyncWhy') ?? '';

  /// The gate's own state ("sec ok", or that its self test failed).
  static Future<String> gate() async =>
      await _ch.invokeMethod<String>('irGate') ?? '';

  /// The tunnel as the list shows it: id / on / ms / up / down.
  static Future<Map<String, String>> live() async {
    final m = await _ch.invokeMethod<Map>('irLive');
    return {for (final e in (m ?? {}).entries) '${e.key}': '${e.value}'};
  }

  /// The two-letter country a probe measured for this row; "" until known.
  static Future<void> resetTotals() async =>
      await _ch.invokeMethod<void>('irTotalReset');

  static Future<void> statReset(String id) async =>
      await _ch.invokeMethod<void>('irStatReset', {'id': id});

  static Future<Map<String, String>> flagsAll() async {
    final m = await _ch.invokeMethod<Map>('irFlagsAll');
    return {for (final e in (m ?? {}).entries) '${e.key}': '${e.value}'};
  }

  static Future<Map<String, int>> ping(List<String> ids) async {
    final m = await _ch.invokeMethod<Map>('irPing', {'ids': ids});
    return {
      for (final e in (m ?? {}).entries)
        '${e.key}': e.value is int ? e.value as int : int.tryParse('${e.value}') ?? -1,
    };
  }

  static Future<Map<String, String>> statsAll() async {
    final m = await _ch.invokeMethod<Map>('irStatsAll');
    return {for (final e in (m ?? {}).entries) '${e.key}': '${e.value}'};
  }

  static Future<String> flagOf() async =>
      await _ch.invokeMethod<String>('irFlagOf') ?? '';

  static Future<String> flag(String id) async =>
      await _ch.invokeMethod<String>('irFlag', {'id': id}) ?? '';

  /// Each named row's last session: "ms|up|down".
  static Future<Map<String, String>> stats(List<String> ids) async {
    final m = await _ch.invokeMethod<Map>('irStats', {'ids': ids});
    return {for (final e in (m ?? {}).entries) '${e.key}': '${e.value}'};
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
