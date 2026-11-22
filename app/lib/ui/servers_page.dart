import 'dart:async';

import 'package:flutter_svg/flutter_svg.dart';
import 'package:material_ui/material_ui.dart';

import '../app_settings.dart';
import '../core/core_controller.dart';
import '../core/ir_bridge.dart';
import '../core/ir_servers.dart';

/// The server list, fed by our own store instead of Mozilla's locations: rows come from the
/// native gate (category, name, note), grouped by category. A tap names the row the VPN dials;
/// nothing here talks to the Mozilla API.
class ServersPage extends StatefulWidget {
  const ServersPage({super.key, required this.core, required this.settings});

  final CoreController core;
  final AppSettings settings;

  @override
  State<ServersPage> createState() => _ServersPageState();
}

class _ServersPageState extends State<ServersPage> {
  final _servers = IrServers();
  String? _picked;
  bool _alive = true;

  /// The tunnel as the list shows it, refreshed once a second while the page
  /// is open; only a change redraws.
  Map<String, String> _live = const {};
  Map<String, String> _stats = const {};
  Timer? _ticker;
  Timer? _flags;
  final _cc = <String, String>{};
  int _probeAt = 0;

  @override
  void initState() {
    super.initState();
    _servers.addListener(() { if (_alive) setState(() {}); });
    // after the first frame: refresh notifies synchronously at its start, and setState during
    // initState is the exception the two widget tests hit.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_alive) return;
      IrRowsBridge.picked().then((id) {
        if (_alive && id.isNotEmpty) setState(() => _picked = id);
      }, onError: (Object _) {});
      // Opening the page reads the library; it does not ask the worker.
      // The exchange happens when the list is pulled or Sync now is pressed,
      // or when the library is still empty (nothing to read).
      _servers.load().then((_) async {
        if (!_alive) return;
        if (_servers.rows.isEmpty) await _servers.refresh();
        if (_alive) _loadStats();
        if (!_alive || _picked != null || _servers.rows.isEmpty) return;
        // a list nobody has chosen from yet dials its first row
        final id = _servers.rows.first.id;
        setState(() => _picked = id);
        IrRowsBridge.pick(id).catchError((Object _) {});
      });
      _loadCached();
      _loadStats();
      _ticker = Timer.periodic(const Duration(seconds: 1), (_) => _pollLive());
      // country flags: one row at a time, only while the tunnel is down
      _flags = Timer.periodic(const Duration(milliseconds: 400), (_) => _probeNext());
    });
  }

  Future<void> _probeNext() async {
    if (!_alive || _live['on'] == 'true') return;
    final rows = _servers.rows;
    while (_probeAt < rows.length && _cc.containsKey(rows[_probeAt].id)) _probeAt++;
    if (_probeAt >= rows.length || _probeAt >= 300) return;
    final id = rows[_probeAt].id;
    final cc = await IrRowsBridge.flag(id);
    if (!_alive) return;
    if (cc.length == 2) setState(() => _cc[id] = cc.toLowerCase());
    _probeAt++;
  }

  /// What an earlier visit already learned: flags and sessions show at once,
  /// before a single probe runs.
  Future<void> _loadCached() async {
    try {
      final f = await IrRowsBridge.flagsAll();
      final st = await IrRowsBridge.statsAll();
      if (!_alive) return;
      setState(() {
        for (final e in f.entries) { _cc[e.key] = e.value.toLowerCase(); }
        if (st.isNotEmpty) _stats = {..._stats, ...st};
      });
    } catch (_) {}
  }

  /// Each row's last session, so the line is there before anything connects.
  Future<void> _loadStats() async {
    final ids = [for (final r in _servers.rows.take(400)) r.id];
    if (ids.isEmpty || !_alive) return;
    try {
      final m = await IrRowsBridge.stats(ids);
      if (_alive && m.isNotEmpty) setState(() => _stats = m);
    } catch (_) {}
  }

  Future<void> _pollLive() async {
    if (!_alive) return;
    try {
      final m = await IrRowsBridge.live();
      if (!_alive) return;
      if (m['id'] != _live['id'] ||
          m['on'] != _live['on'] ||
          m['ms'] != _live['ms'] ||
          m['up'] != _live['up'] ||
          m['down'] != _live['down']) {
        setState(() => _live = m);
      }
    } catch (_) {}
  }

  /// The line under a row: the live session while it is the tunnel, or the
  /// last one it had.
  String? _lineFor(String id) {
    if (_live['id'] == id && _live['on'] == 'true') {
      return '${_hm(int.tryParse(_live['ms'] ?? '') ?? 0)} · '
          '↑ ${_bytes(int.tryParse(_live['up'] ?? '') ?? 0)} · '
          '↓ ${_bytes(int.tryParse(_live['down'] ?? '') ?? 0)}';
    }
    final s = _stats[id];
    if (s == null) return null;
    final p = s.split('|');
    if (p.length != 3) return null;
    return 'last ${_hm(int.tryParse(p[0]) ?? 0)} · '
        '↑ ${_bytes(int.tryParse(p[1]) ?? 0)} · '
        '↓ ${_bytes(int.tryParse(p[2]) ?? 0)}';
  }

  static String _hm(int ms) {
    final s = ms ~/ 1000;
    final h = s ~/ 3600;
    final m = (s % 3600) ~/ 60;
    final sec = s % 60;
    return h > 0
        ? '$h:${m.toString().padLeft(2, '0')}:${sec.toString().padLeft(2, '0')}'
        : '${m.toString().padLeft(2, '0')}:${sec.toString().padLeft(2, '0')}';
  }

  static String _bytes(int b) {
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    var v = b.toDouble();
    var i = 0;
    while (v >= 1024 && i < units.length - 1) {
      v /= 1024;
      i++;
    }
    return i == 0 ? '$b ${units[0]}' : '${v.toStringAsFixed(1)} ${units[i]}';
  }

  @override
  void dispose() {
    _alive = false;
    _ticker?.cancel();
    _flags?.cancel();
    _servers.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final groups = _servers.byCategory();
    return Scaffold(
      body: RefreshIndicator(
      key: const Key('ir-servers'),
      onRefresh: _servers.refresh,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
        children: [
          Row(
            children: [
              _servers.loading ? const SizedBox.shrink() : const SizedBox.shrink(),
              const Spacer(),
              if (_servers.loading)
                const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
              const SizedBox(width: 8),
              Text('${_servers.rows.length}',
                  style: theme.textTheme.labelMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            ],
          ),
          if (_servers.error != null) ...[
            const SizedBox(height: 8),
            Text(_servers.error!, style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error)),
          ],
          if (_servers.rows.isEmpty && !_servers.loading) ...[
            const SizedBox(height: 24),
            Text('No servers yet', style: theme.textTheme.bodyMedium),
            // An empty store has two very different reasons; the page names
            // which one, so the next step is not a guess.
            if (_servers.gateNote.isNotEmpty && _servers.gateNote != 'sec ok')
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(
                  _servers.gateNote,
                  style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error),
                ),
              ),
            TextButton(onPressed: _servers.refresh, child: const Text('Sync now')),
          ],
          for (final entry in groups.entries) ...[
            const SizedBox(height: 18),
            Text(entry.key,
                style: theme.textTheme.titleSmall?.copyWith(
                  fontWeight: FontWeight.w700,
                  color: theme.colorScheme.onSurfaceVariant,
                )),
            const SizedBox(height: 6),
            Card(
              child: Column(
                children: [
                  for (final row in entry.value)
                    ListTile(
                      dense: true,
                      leading: _cc[row.id] == null
                          ? null
                          : ClipRRect(
                              borderRadius: BorderRadius.circular(7),
                              child: SvgPicture.asset(
                              'assets/flags/${_cc[row.id]}.svg',
                              width: 40,
                              height: 28,
                            )),
                      title: Text(row.name),
                      subtitle: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(row.note.isEmpty ? row.id : row.note),
                          if (_lineFor(row.id) case final line?)
                            Text(
                              line,
                              style: theme.textTheme.bodySmall?.copyWith(
                                color: theme.colorScheme.onSurfaceVariant,
                              ),
                            ),
                        ],
                      ),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (_live[row.id] == null && _stats[row.id] != null)
                            IconButton(
                              tooltip: 'Reset traffic',
                              visualDensity: VisualDensity.compact,
                              icon: Icon(Icons.restart_alt_rounded,
                                  size: 18, color: theme.colorScheme.onSurfaceVariant),
                              onPressed: () async {
                                await IrRowsBridge.statReset(row.id);
                                if (!_alive) return;
                                setState(() => _stats = Map<String, String>.from(_stats)..remove(row.id));
                              },
                            ),
                          if (_picked == row.id) const Icon(Icons.check),
                        ],
                      ),
                      onTap: () {
                        setState(() => _picked = row.id);
                        IrRowsBridge.pick(row.id).catchError((Object _) {});
                      },
                    ),
                ],
              ),
            ),
          ],
        ],
      ),
      ),
    );
  }
}
