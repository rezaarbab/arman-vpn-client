import 'package:flutter/foundation.dart';

import 'ir_bridge.dart';

/// One row of our store, as the screens need it.
@immutable
class IrServer {
  const IrServer({required this.id, required this.name, required this.category, required this.note});

  final String id;
  final String name;
  final String category;
  final String note;

  factory IrServer.fromMap(Map<String, String> m) => IrServer(
        id: m['id'] ?? '',
        name: m['name'] ?? '',
        category: m['cat'] ?? '',
        note: m['msg'] ?? '',
      );
}

/// The list the screens will read instead of Mozilla's countries. Refreshing asks the gate for
/// one exchange and then re-reads the store; nothing here talks to the network itself.
class IrServers extends ChangeNotifier {
  bool _gone = false;
  void _notify() { if (!_gone) notifyListeners(); }

  @override
  void dispose() {
    _gone = true;
    super.dispose();
  }

  List<IrServer> rows = const [];
  bool loading = false;
  String? error;

  /// The gate's own state when the store came back empty ("sec ok", or that
  /// its self test failed): an empty page means one of two things, and the
  /// page should say which.
  String gateNote = '';

  /// Reads what the library already holds; no exchange. Opening the page
  /// costs no request: the list is asked for by hand (pull, or Sync now).
  Future<void> load() async {
    loading = true;
    _notify();
    try {
      final raw = await IrRowsBridge.all();
      rows = raw.map(IrServer.fromMap).where((r) => r.id.isNotEmpty).toList(growable: false);
      error = null;
    } catch (e) {
      error = '$e';
    } finally {
      loading = false;
      _notify();
    }
  }

  Future<void> refresh() async {
    loading = true;
    _notify();
    try {
      final code = await IrRowsBridge.refresh();
      final raw = await IrRowsBridge.all();
      rows = raw.map(IrServer.fromMap).where((r) => r.id.isNotEmpty).toList(growable: false);
      if (code < 0) {
        final why = await IrRowsBridge.why();
        error = why.isEmpty ? 'refresh failed' : why;
      } else {
        error = null;
        gateNote = rows.isEmpty ? await IrRowsBridge.gate() : '';
      }
    } catch (e) {
      error = '$e';
    } finally {
      loading = false;
      _notify();
    }
  }

  Map<String, List<IrServer>> byCategory() {
    final out = <String, List<IrServer>>{};
    for (final r in rows) {
      (out[r.category.isEmpty ? 'Default' : r.category] ??= []).add(r);
    }
    return out;
  }
}
