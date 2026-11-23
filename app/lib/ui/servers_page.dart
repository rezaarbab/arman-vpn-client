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

bool _autoSyncedOnce = false;

class _ServersPageState extends State<ServersPage> {
  final _servers = IrServers();
  final _search = TextEditingController();
  String? _picked;
  bool _alive = true;
  Map<String, int> _ping = const {};
  bool _sortPing = false;
  bool _busy = false;
  bool _searchOpen = false;
  bool _pinging = false;

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
        if (_alive) _loadStats();
        if (!_alive || _picked != null || _servers.rows.isEmpty) return;
        // a list nobody has chosen from yet dials its first row
        final id = _servers.rows.first.id;
        setState(() => _picked = id);
        IrRowsBridge.pick(id).catchError((Object _) {});
      });
      _loadCached();
      _loadStats();
      _loadPings();
      _ticker = Timer.periodic(const Duration(seconds: 1), (_) => _pollLive());
      // country flags: one row at a time, only while the tunnel is down
      _flags = Timer.periodic(const Duration(milliseconds: 400), (_) => _probeNext());
    });
  }

  /// A real connect timed in C, a batch at a time so the list fills as it goes.
  Future<void> _pingAll() async {
    if (!_alive || _pinging) return;
    setState(() => _pinging = true);
    final ids = [for (final r in _servers.rows.take(48)) r.id];
    for (var i = 0; i < ids.length; i += 12) {
      if (!_alive) return;
      final end = i + 12 > ids.length ? ids.length : i + 12;
      final part = ids.sublist(i, end);
      try {
        final m = await IrRowsBridge.ping(part);
        if (!_alive) return;
        setState(() => _ping = {..._ping, ...m});
      } catch (_) {}
    }
    if (mounted) setState(() => _pinging = false);
  }

  /// The button: ask the worker now, keep everything else as it was.
  Future<void> _update() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await _servers.refresh();
      if (!_alive) return;
      _loadStats();
      _loadCached();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
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

  Future<void> _loadPings() async {
    try {
      final m = await IrRowsBridge.pingsAll();
      if (_alive && m.isNotEmpty) setState(() => _ping = {...m, ..._ping});
    } catch (_) {}
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
    _search.dispose();
    _servers.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final q = _search.text.trim().toLowerCase();
    final groups = <String, List<IrServer>>{};
    for (final r in _servers.rows) {
      final cc = (_cc[r.id] ?? '').toLowerCase();
      final country = cc.isEmpty ? '' : (_countryNames[cc] ?? '').toLowerCase();
      if (q.isNotEmpty &&
          !r.name.toLowerCase().contains(q) &&
          !(cc.isNotEmpty && cc.contains(q)) &&
          !(country.isNotEmpty && country.contains(q))) {
        continue;
      }
      (groups[r.category.isEmpty ? 'Default' : r.category] ??= []).add(r);
    }
    if (_sortPing) {
      int key(IrServer r) =>
          _ping[r.id] == null || _ping[r.id]! < 0 ? 1 << 30 : _ping[r.id]!;
      for (final e in groups.values) {
        e.sort((a, b) => key(a).compareTo(key(b)));
      }
    }
    return Scaffold(
      body: RefreshIndicator(
      key: const Key('ir-servers'),
      onRefresh: _servers.refresh,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
        children: [
          Row(
            children: [
              if (_searchOpen)
                Expanded(
                  child: TextField(
                    controller: _search,
                    autofocus: true,
                    onChanged: (_) => setState(() {}),
                    decoration: InputDecoration(
                      isDense: true,
                      hintText: 'Search name or country',
                      prefixIcon: const Icon(Icons.search_rounded, size: 18),
                      suffixIcon: IconButton(
                        iconSize: 16,
                        visualDensity: VisualDensity.compact,
                        icon: const Icon(Icons.close_rounded),
                        onPressed: () => setState(() {
                          _searchOpen = false;
                          _search.clear();
                        }),
                      ),
                    ),
                  ),
                )
              else ...[
                IconButton(
                  tooltip: 'Search',
                  visualDensity: VisualDensity.compact,
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints.tightFor(width: 30, height: 30),
                  iconSize: 18,
                  icon: const Icon(Icons.search_rounded),
                  onPressed: () => setState(() => _searchOpen = true),
                ),
                const Spacer(),
              ],
              IconButton(
                tooltip: 'Ping all',
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints.tightFor(width: 30, height: 30),
                iconSize: 18,
                icon: _pinging
                    ? const SizedBox(
                        width: 14,
                        height: 14,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.bolt_rounded),
                onPressed: _pingAll,
              ),
              IconButton(
                tooltip: 'Update the list',
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints.tightFor(width: 30, height: 30),
                iconSize: 18,
                icon: _busy
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.refresh_rounded),
                onPressed: _busy ? null : _update,
              ),
              IconButton(
                tooltip: 'Sort by ping',
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints.tightFor(width: 30, height: 30),
                iconSize: 18,
                icon: Icon(_sortPing ? Icons.sort_rounded : Icons.sort_outlined),
                onPressed: () => setState(() => _sortPing = !_sortPing),
              ),
            ],
          ),
          Row(
            children: [
              IconButton(
                tooltip: 'Reset all traffic',
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints.tightFor(width: 30, height: 30),
                iconSize: 18,
                icon: const Icon(Icons.cleaning_services_outlined),
                onPressed: () async {
                  await IrRowsBridge.statsResetAll();
                  if (!_alive) return;
                  setState(() => _stats = const {});
                },
              ),
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
                          if (_ping[row.id] != null)
                            Padding(
                              padding: const EdgeInsets.only(right: 6),
                              child: _PingPill(ms: _ping[row.id]!),
                            ),
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


/// The names people search by, for the countries the flags cover most.
const _countryNames = <String, String>{
  'de': 'Germany', 'nl': 'Netherlands', 'fr': 'France', 'gb': 'United Kingdom',
  'us': 'United States', 'ca': 'Canada', 'tr': 'Turkey', 'ir': 'Iran',
  'fi': 'Finland', 'se': 'Sweden', 'no': 'Norway', 'dk': 'Denmark',
  'it': 'Italy', 'es': 'Spain', 'pt': 'Portugal', 'at': 'Austria',
  'be': 'Belgium', 'ch': 'Switzerland', 'pl': 'Poland', 'cz': 'Czechia',
  'hu': 'Hungary', 'ro': 'Romania', 'bg': 'Bulgaria', 'gr': 'Greece',
  'ru': 'Russia', 'ua': 'Ukraine', 'kz': 'Kazakhstan', 'ae': 'United Arab Emirates',
  'sa': 'Saudi Arabia', 'qa': 'Qatar', 'il': 'Israel', 'in': 'India',
  'jp': 'Japan', 'kr': 'South Korea', 'sg': 'Singapore', 'hk': 'Hong Kong',
  'tw': 'Taiwan', 'cn': 'China', 'th': 'Thailand', 'vn': 'Vietnam',
  'my': 'Malaysia', 'id': 'Indonesia', 'ph': 'Philippines', 'au': 'Australia',
  'nz': 'New Zealand', 'br': 'Brazil', 'ar': 'Argentina', 'cl': 'Chile',
  'mx': 'Mexico', 'co': 'Colombia', 'pe': 'Peru', 'za': 'South Africa',
  'eg': 'Egypt', 'ma': 'Morocco', 'ng': 'Nigeria', 'ke': 'Kenya',
  'am': 'Armenia', 'ge': 'Georgia', 'az': 'Azerbaijan', 'by': 'Belarus',
  'md': 'Moldova', 'lt': 'Lithuania', 'lv': 'Latvia', 'ee': 'Estonia',
  'cy': 'Cyprus', 'mt': 'Malta', 'lu': 'Luxembourg', 'is': 'Iceland',
  'ie': 'Ireland', 'hr': 'Croatia', 'rs': 'Serbia', 'sk': 'Slovakia',
  'si': 'Slovenia', 'al': 'Albania', 'mk': 'North Macedonia', 'ba': 'Bosnia',
};


class _PingPill extends StatelessWidget {
  const _PingPill({required this.ms});
  final int ms;
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final dead = ms < 0;
    final color = dead
        ? theme.colorScheme.outline
        : ms < 150
            ? const Color(0xFF2E9E5B)
            : ms < 400
                ? const Color(0xFFB07B12)
                : const Color(0xFFC0553F);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(9),
      ),
      child: Text(
        dead ? '—' : '$ms ms',
        style: theme.textTheme.labelSmall?.copyWith(
          color: color,
          fontWeight: FontWeight.w800,
        ),
      ),
    );
  }
}
