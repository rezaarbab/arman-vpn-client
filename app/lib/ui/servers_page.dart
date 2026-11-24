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
  final _search = TextEditingController();
  String? _picked;
  bool _alive = true;
  bool _sortPing = false;
  bool _busy = false;
  bool _searchOpen = false;
  bool _pinging = false;

  /// The tunnel as the list shows it, refreshed once a second while the page
  /// is open; only a change redraws.
  Map<String, String> _live = const {};
  Timer? _ticker;
  bool _flagging = false;

  /// What this run knows - the measured delays, the countries, the last
  /// sessions - kept between pages: opening the list again after a visit to
  /// Settings must not blank the flags and the numbers until the reads come
  /// back a few seconds later.
  static final Map<String, int> _ping = <String, int>{};
  static final Map<String, String> _stats = <String, String>{};
  static final Map<String, String> _cc = <String, String>{};

  /// Rows this run already asked about. A country is measured once per run:
  /// a row that did not answer is not asked again every time the page opens.
  static final _asked = <String>{};

  /// Whether the list has already been asked for the worker's answer in this
  /// run - the "first time only" choice needs the memory.
  static bool _syncedOnce = false;

  /// "CC|address" for the rows whose own server was identified.
  static final Map<String, String> _srv = <String, String>{};

  /// Rows being identified right now.
  final _locating = <String>{};

  /// The rows the user marked. Kept by the phone, so they are there on the
  /// next start; the marked ones come first and can be pinged on their own.
  static final _pins = <String>{};

  Future<void> _loadPins() async {
    try {
      final l = await IrRowsBridge.pins();
      if (!_alive) return;
      setState(() {
        _pins
          ..clear()
          ..addAll(l);
      });
    } catch (_) {}
  }

  /// Servers the phone is holding back because they were removed. The number
  /// is on the bar beside Update, because the person who deleted one and
  /// presses Update is looking here, and not in Settings.
  int _removed = 0;

  Future<void> _loadRemoved() async {
    try {
      final n = await IrRowsBridge.dropCount();
      if (_alive) setState(() => _removed = n);
    } catch (_) {}
  }

  Future<void> _bringBack() async {
    if (!_alive || _busy) return;
    // undrop forgets the removals; the list then needs the rows themselves,
    // which only an exchange with the server can hand over - which is exactly
    // what the Update button does, so it is asked to do it.
    await IrRowsBridge.undrop();
    await _update();
    _loadRemoved();
  }

  Future<void> _togglePin(String id) async {
    final on = !_pins.contains(id);
    setState(() {
      if (on) {
        _pins.add(id);
      } else {
        _pins.remove(id);
      }
    });
    try {
      final kept = await IrRowsBridge.pin(id, on);
      if (!_alive) return;
      setState(() {
        _pins
          ..clear()
          ..addAll(kept);
      });
    } catch (_) {}
  }

  /// Pings only the marked rows - the button that asks for them appears once
  /// there is something marked, and never when there is nothing to ask.
  Future<void> _pingPinned() => _pingAll(only: [
        for (final r in _servers.rows)
          if (_pins.contains(r.id)) r.id,
      ]);

  /// The flag's slot. A row that was asked and never answered gets a mark of
  /// its own: an empty space reads as "still loading" forever, and the honest
  /// answer for that host is "no country could be had from it".
  Widget _flagSlot(IrServer row, ThemeData theme) {
    final cc = _cc[row.id];
    if (cc != null && !_isDead(row.id)) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(7),
        child: SvgPicture.asset('assets/flags/$cc.svg', width: 40, height: 28),
      );
    }
    if (_isDead(row.id)) return const SizedBox.shrink();
    if (!_asked.contains(row.id) && !_locating.contains(row.id)) {
      return const SizedBox.shrink();
    }
    // Asked, and no country came back: the same failure mark the ping pill
    // uses, so "nobody knows yet" and "it could not be had" are not the same
    // empty space. He asked for a flag that says "this one is broken".
    final err = theme.colorScheme.error;
    return Tooltip(
      message: 'No country could be had from this server',
      child: Container(
        width: 40,
        height: 28,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: err.withValues(alpha: 0.10),
          borderRadius: BorderRadius.circular(7),
          border: Border.all(color: err.withValues(alpha: 0.45)),
        ),
        child: Icon(Icons.error_outline_rounded, size: 15, color: err),
      ),
    );
  }

  /// One row's server: where it is and what address that is. A DNS answer and
  /// one page - no core - so it is fast and works with the tunnel up or down.
  Future<void> _identify(String id) async {
    if (!_alive || _locating.contains(id)) return;
    setState(() => _locating.add(id));
    try {
      final seen = await IrRowsBridge.locate(id);
      if (!_alive) return;
      setState(() {
        if (seen.isNotEmpty) {
          _srv[id] = seen;
          _cc[id] = seen.split('|').first.toLowerCase();
        }
        _locating.remove(id);
      });
    } catch (_) {
      if (_alive) setState(() => _locating.remove(id));
    }
  }

  /// Whether this row has a session to reset: a row that carried nothing has
  /// no reset button, which is what makes the button mean something.
  bool _hasTraffic(String id) {
    if (_live['id'] == id && _live['on'] == 'true') return true;
    final s = _stats[id];
    if (s == null) return false;
    final p = s.split('|');
    if (p.length != 3) return false;
    return (int.tryParse(p[1]) ?? 0) + (int.tryParse(p[2]) ?? 0) > 0;
  }

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
      final cached = _loadCached();
      _servers.load().then((_) async {
        if (!_alive) return;
        _loadStats();
        if (_picked == null && _servers.rows.isNotEmpty) {
          // a list nobody has chosen from yet dials its first row
          final id = _servers.rows.first.id;
          setState(() => _picked = id);
          IrRowsBridge.pick(id).catchError((Object _) {});
        }
        await cached;
        // The panel's commands over this list's own behaviour are read before
        // anything is decided: they arrive with the sync and say whether the
        // app may measure on its own.
        await IrRowsBridge.adminFlags();
        // how often opening the list asks the worker is the user's choice in
        // General settings; the first time is the default because a fresh
        // install has nothing to look at until something arrives
        final mode = widget.settings.syncOnEntry;
        if (mode == 2 || (mode == 1 && !_syncedOnce)) {
          _syncedOnce = true;
          unawaited(_update());
        }
        if (_autoFlags) _flagPass();
        if (_autoPing && _ping.isEmpty) _pingAll();
      });
      _loadPings();
      _loadPins();
      _loadRemoved();
      _ticker = Timer.periodic(const Duration(seconds: 1), (_) => _pollLive());
    });
  }

  /// The countries the store does not have, three rows at a time. A country is
  /// one short-lived core, so the old one-row-per-tick pass made the last row
  /// wait behind every other row on the list.
  Future<void> _flagPass({bool force = false}) async {
    if (_flagging) return;
    _flagging = true;
    try {
      var at = 0;
      // the rows a person can actually see, not the whole store: a pass over
      // hundreds of hosts is what made "update" feel like it never finished.
      // The globe button in the bar is the one that asks for the long tail.
      final ids = [for (final r in _servers.rows.take(60)) r.id];
      while (at < ids.length) {
        // A country is asked for with the tunnel up as well: the probe core has
        // its own local port and this app's sockets are outside the VPN by
        // choice, so the flags do not stop coming just because one is on.
        if (!_alive) return;
        final chunk = <String>[];
        while (chunk.length < 3 && at < ids.length) {
          final id = ids[at++];
          if (_cc.containsKey(id) || _isDead(id)) continue;
          if (!force && _asked.contains(id)) continue;
          chunk.add(id);
        }
        if (chunk.isEmpty) continue;
        _asked.addAll(chunk);
        final got = await Future.wait([for (final id in chunk) IrRowsBridge.flag(id)]);
        if (!_alive) return;
        final add = <String, String>{};
        for (var i = 0; i < chunk.length; i++) {
          final cc = got[i];
          if (cc.length == 2) add[chunk[i]] = cc.toLowerCase();
        }
        if (add.isNotEmpty) setState(() => _cc.addAll(add));
      }
    } catch (_) {
      // a page without a flag is still a page of servers
    } finally {
      _flagging = false;
    }
  }

  /// A real connect timed in C, a few rows at a time so the list fills as it goes.
  Future<void> _pingAll({List<String>? only}) async {
    if (!_alive || _pinging) return;
    setState(() => _pinging = true);
    final ids = only ?? [for (final r in _servers.rows.take(48)) r.id];
    for (var i = 0; i < ids.length; i += 12) {
      if (!_alive) return;
      // three calls at once, on the three probe threads; each does a few rows
      final runs = <Future<Map<String, int>>>[];
      final parts = <List<String>>[];
      for (var k = 0; k < 3; k++) {
        final start = i + k * 4;
        if (start >= ids.length) break;
        final end = start + 4 > ids.length ? ids.length : start + 4;
        parts.add(ids.sublist(start, end));
        runs.add(IrRowsBridge.ping(parts.last));
      }
      setState(() => _busyIds.addAll(parts.expand((p) => p)));
      for (var j = 0; j < runs.length; j++) {
        try {
          final m = await runs[j];
          if (!_alive) return;
          setState(() => _ping.addAll(m));
        } catch (_) {}
        // the mark goes away as that group's number arrives, so the list fills
        // row by row instead of all at once at the end
        if (_alive) setState(() => _busyIds.removeAll(parts[j]));
      }
    }
    // The ping round carried the country and the exit IP with it: read them
    // out of the store so the flags arrive with the numbers, not after them.
    if (_alive) _loadCached();
    if (mounted) setState(() => _pinging = false);
  }

  /// Rows whose delay is being measured right now, so the number that is on
  /// its way shows as on its way.
  final _busyIds = <String>{};

  /// One row's delay, asked by its own button. The same round trip carries the
  /// country and the exit address, so this fills the flag too.
  Future<void> _pingOne(String id) async {
    if (!_alive || _busyIds.contains(id)) return;
    setState(() => _busyIds.add(id));
    try {
      final m = await IrRowsBridge.ping([id]);
      if (_alive) setState(() => _ping.addAll(m));
      await _loadCached();
    } catch (_) {
    } finally {
      if (_alive) setState(() => _busyIds.remove(id));
    }
  }

  /// The button: ask the worker now, keep everything else as it was.
  Future<void> _update() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await _servers.refresh();
      if (!_alive) return;
      _loadStats();
      _loadCached().then((_) {
        // the panel's commands still decide: refreshing the list is the
        // person's request, measuring it afterwards is the app's own
        if (_autoFlags) _flagPass();
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _loadPings() async {
    try {
      final m = await IrRowsBridge.pingsAll();
      // what a ping round already measured for this visit wins over the store
      if (_alive && m.isNotEmpty) {
        setState(() {
          for (final e in m.entries) {
            _ping.putIfAbsent(e.key, () => e.value);
          }
        });
      }
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
        if (st.isNotEmpty) _stats.addAll(st);
      });
    } catch (_) {}
  }

  /// Measured, and it answered nothing. -2 is something else entirely - nothing
  /// could be measured, usually because a second core would not start beside the
  /// tunnel - and calling that dead is how a whole list ended up "removed".
  bool _isDead(String id) => _ping[id] == -1;

  /// Whether the app may run the automatic pass itself. The panel's two commands
  /// for it - on start, and for the servers this run has never seen - both have
  /// to allow it, and a worker that says nothing ("a") leaves the choice with the
  /// user's own switch. A button the person presses is never gated: these
  /// commands are about what the app does on its own.
  bool get _autoFlags => _allowed(
    IrRowsBridge.adminFlag(IrRowsBridge.adminFlagStart),
    widget.settings.autoFlags,
  ) &&
      _allowed(
        IrRowsBridge.adminFlag(IrRowsBridge.adminFlagNew),
        widget.settings.autoFlags,
      );

  bool get _autoPing => _allowed(
    IrRowsBridge.adminFlag(IrRowsBridge.adminPingStart),
    widget.settings.autoPing,
  ) &&
      _allowed(
        IrRowsBridge.adminFlag(IrRowsBridge.adminPingNew),
        widget.settings.autoPing,
      );

  static bool _allowed(String adm, bool user) =>
      adm == '1' || (adm != '0' && user);

  /// A row nobody pinged is not dead, only unknown.
  bool _isUnknown(String id) => !_ping.containsKey(id) || _ping[id] == -2;

  /// The row's own line: what the panel calls it, and after the "|" the
  /// address that an identify round found for it.
  String _noteLine(IrServer row) {
    final base = row.note.isEmpty ? row.id : row.note;
    final seen = _srv[row.id];
    if (seen == null) return base;
    final at = seen.indexOf('|');
    return at < 0 ? base : '$base · ${seen.substring(at + 1)}';
  }

  /// The rows this run measured and that did not answer. A row nobody pinged
  /// is not dead, only unknown, so it is never in this list.
  List<String> get _dead => [
        for (final r in _servers.rows)
          if (_isDead(r.id)) r.id,
      ];

  /// Out of the store, out of the sealed copy, out of the caches: a deleted
  /// server stays out, including through the next sync.
  Future<void> _remove(List<String> ids) async {
    if (ids.isEmpty || !_alive) return;
    final one = ids.length == 1;
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(one ? 'Remove this server?' : 'Remove ${ids.length} dead servers?'),
        content: Text(
          one
              ? 'It leaves the list and the stored copy. The restore button in the bar brings it back.'
              : 'These did not answer a ping. They leave the list and the stored copy, and the next sync will not bring them back. '
                  'If that turns out to be wrong, the restore button in the bar puts them back.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Remove'),
          ),
        ],
      ),
    );
    if (ok != true || !_alive) return;
    final did = await _servers.dropAll(ids);
    if (!_alive) return;
    // The phone can refuse (a removal that would leave nothing). Then the list
    // must keep showing the servers it still has, not pretend they went.
    if (!did) return;
    setState(() {
      for (final id in ids) {
        _ping.remove(id);
        _cc.remove(id);
        _stats.remove(id);
        _asked.remove(id);
        _srv.remove(id);
      }
    });
    // so the bar learns there is now something to bring back
    _loadRemoved();
  }

  /// Each row's last session, so the line is there before anything connects.
  Future<void> _loadStats() async {
    final ids = [for (final r in _servers.rows.take(400)) r.id];
    if (ids.isEmpty || !_alive) return;
    try {
      final m = await IrRowsBridge.stats(ids);
      if (_alive && m.isNotEmpty) setState(() => _stats.addAll(m));
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
    // one letter, no space: "1.2 MB" is long enough to push the line under the
    // name onto a second row, and the row's height is the list's cost
    const units = ['B', 'K', 'M', 'G', 'T'];
    var v = b.toDouble();
    var i = 0;
    while (v >= 1024 && i < units.length - 1) {
      v /= 1024;
      i++;
    }
    return i == 0 ? '$b${units[0]}' : '${v.toStringAsFixed(1)}${units[i]}';
  }

  @override
  void dispose() {
    _alive = false;
    _ticker?.cancel();
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
    // One comparison, in one pass: the marked rows lead, and within them the
    // order the user asked for. Sorting twice with Dart's unstable sort would
    // let the second pass undo the first.
    int pinKey(IrServer r) => _pins.contains(r.id) ? 0 : 1;
    int pingKey(IrServer r) =>
        _ping[r.id] == null || _ping[r.id]! < 10 ? 1 << 30 : _ping[r.id]!;
    for (final e in groups.values) {
      e.sort((a, b) {
        final p = pinKey(a).compareTo(pinKey(b));
        if (p != 0) return p;
        return _sortPing ? pingKey(a).compareTo(pingKey(b)) : 0;
      });
    }
    // The reset buttons appear where there is something to reset: a row that
    // carried nothing does not need a button that would do nothing.
    final anyTraffic = _live['on'] == 'true' || _stats.keys.any(_hasTraffic);

    // One control of the page's single bar: a row of 28dp boxes. Two bars of
    // buttons above the list cost the height of three servers.
    Widget btn(String tip, Widget icon, VoidCallback? onTap) => IconButton(
          tooltip: tip,
          visualDensity: VisualDensity.compact,
          padding: EdgeInsets.zero,
          constraints: const BoxConstraints.tightFor(width: 28, height: 28),
          iconSize: 18,
          icon: icon,
          onPressed: onTap,
        );
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
                btn(
                  'Search',
                  const Icon(Icons.search_rounded),
                  () => setState(() => _searchOpen = true),
                ),
                btn(
                  'Ping all',
                  _pinging
                      ? const SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.bolt_rounded),
                  _pingAll,
                ),
                if (_pins.isNotEmpty)
                  btn(
                    'Ping the marked servers',
                    const Icon(Icons.push_pin_rounded),
                    _pingPinned,
                  ),
                btn(
                  'Update the list',
                  _busy
                      ? const SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.refresh_rounded),
                  _busy ? null : _update,
                ),
                // Only when something is being held back: the bar has no room
                // for a button that does nothing most of the time.
                if (_removed > 0)
                  btn(
                    'Bring back the $_removed removed server(s)',
                    const Icon(Icons.restore_rounded),
                    _busy ? null : _bringBack,
                  ),
                btn(
                  'Sort by ping',
                  Icon(_sortPing ? Icons.sort_rounded : Icons.sort_outlined),
                  () => setState(() => _sortPing = !_sortPing),
                ),
                btn(
                  'Identify the servers',
                  const Icon(Icons.public_rounded),
                  () => _flagPass(force: true),
                ),
                if (_dead.isNotEmpty)
                  btn(
                    'Remove ${_dead.length} that did not answer',
                    const Icon(Icons.delete_outline_rounded),
                    () => _remove(_dead),
                  ),
                if (anyTraffic)
                  btn(
                    'Reset all traffic',
                    const Icon(Icons.cleaning_services_outlined),
                    () async {
                      await IrRowsBridge.statsResetAll();
                      if (!_alive) return;
                      setState(_stats.clear);
                    },
                  ),
                const Spacer(),
                if (_servers.loading)
                  const SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                const SizedBox(width: 6),
                Text('${_servers.rows.length}',
                    style: theme.textTheme.labelMedium
                        ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
              ],
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
                      // The flag's slot is there before the flag is: a list
                      // whose names shift sideways as countries arrive is the
                      // same list jumping under a finger.
                      leading: SizedBox(
                        width: 40,
                        height: 28,
                        // a row that did not answer carries no flag either: the
                        // lookup knows where that host sits, but no server is
                        // standing there for this phone
                        child: _flagSlot(row, theme),
                      ),
                      title: Text(row.name,
                          maxLines: 1, overflow: TextOverflow.ellipsis),
                      // The buttons stay on the name's line; the traffic - the
                      // longest words in the row - gets a line of its own, so
                      // it never runs under the buttons.
                      subtitle: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Expanded(
                                child: Text(
                                  _noteLine(row),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                              // The mark: it keeps this row at the top of its
                              // group and lets the bar ping the marked ones.
                              IconButton(
                                tooltip: _pins.contains(row.id)
                                    ? 'Unmark this server'
                                    : 'Mark this server',
                                visualDensity: VisualDensity.compact,
                                padding: EdgeInsets.zero,
                                constraints: const BoxConstraints.tightFor(
                                    width: 26, height: 26),
                                iconSize: 15,
                                icon: Icon(
                                  _pins.contains(row.id)
                                      ? Icons.push_pin_rounded
                                      : Icons.push_pin_outlined,
                                  color: _pins.contains(row.id)
                                      ? theme.colorScheme.primary
                                      : theme.colorScheme.onSurfaceVariant,
                                ),
                                onPressed: () => _togglePin(row.id),
                              ),
                              IconButton(
                                tooltip: 'Identify this server',
                                visualDensity: VisualDensity.compact,
                                padding: EdgeInsets.zero,
                                constraints: const BoxConstraints.tightFor(
                                    width: 26, height: 26),
                                iconSize: 15,
                                icon: _locating.contains(row.id)
                                    ? const SizedBox(
                                        width: 12,
                                        height: 12,
                                        child: CircularProgressIndicator(
                                            strokeWidth: 2),
                                      )
                                    : Icon(Icons.public_rounded,
                                        color: theme.colorScheme.onSurfaceVariant),
                                onPressed: () => _identify(row.id),
                              ),
                              if (_hasTraffic(row.id))
                                IconButton(
                                  tooltip: 'Reset traffic',
                                  visualDensity: VisualDensity.compact,
                                  padding: EdgeInsets.zero,
                                  constraints: const BoxConstraints.tightFor(
                                      width: 26, height: 26),
                                  iconSize: 15,
                                  icon: Icon(Icons.restart_alt_rounded,
                                      color: theme.colorScheme.onSurfaceVariant),
                                  onPressed: () async {
                                    await IrRowsBridge.statReset(row.id);
                                    if (!_alive) return;
                                    setState(() => _stats.remove(row.id));
                                  },
                                ),
                              // Every row can go by hand, not only a dead one:
                              // the ping's verdict is not always known, the wish is.
                              IconButton(
                                tooltip: 'Remove',
                                visualDensity: VisualDensity.compact,
                                padding: EdgeInsets.zero,
                                constraints: const BoxConstraints.tightFor(
                                    width: 26, height: 26),
                                iconSize: 15,
                                icon: Icon(Icons.close_rounded,
                                    color: theme.colorScheme.onSurfaceVariant),
                                onPressed: () => _remove([row.id]),
                              ),
                            ],
                          ),
                          if (_lineFor(row.id) case final line?)
                            Padding(
                              padding: const EdgeInsets.only(top: 2),
                              child: Text(
                                line,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: theme.textTheme.bodySmall?.copyWith(
                                  color: theme.colorScheme.onSurfaceVariant,
                                ),
                              ),
                            ),
                        ],
                      ),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (_busyIds.contains(row.id))
                            const SizedBox(
                              width: 14,
                              height: 14,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          else if (_ping[row.id] case final ms? when ms != -2)
                            Tooltip(
                              message: 'Ping this server again',
                              child: InkWell(
                                onTap: () => _pingOne(row.id),
                                child: _PingPill(ms: ms),
                              ),
                            )
                          else
                            IconButton(
                              tooltip: 'Ping this server',
                              visualDensity: VisualDensity.compact,
                              padding: EdgeInsets.zero,
                              constraints:
                                  const BoxConstraints.tightFor(width: 26, height: 26),
                              iconSize: 16,
                              icon: Icon(Icons.bolt_rounded,
                                  color: theme.colorScheme.onSurfaceVariant),
                              onPressed: () => _pingOne(row.id),
                            ),
                          if (_picked == row.id) const Icon(Icons.check),
                        ],
                      ),
                      onTap: () {
                        setState(() => _picked = row.id);
                        IrRowsBridge.pick(row.id).catchError((Object _) {});
                      },
                      // Held down: this row leaves the list and the stored copy.
                      onLongPress: () => _remove([row.id]),
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
    final dead = ms < 10;
    final color = dead
        ? theme.colorScheme.error
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
      // a server that did not answer is said as a failure, not as a number:
      // the old dash read like a delay that had simply not been taken
      child: dead
          ? Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.error_outline_rounded, size: 12, color: color),
                const SizedBox(width: 3),
                Text(
                  'dead',
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: color,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ],
            )
          : Text(
              '$ms ms',
              style: theme.textTheme.labelSmall?.copyWith(
                color: color,
                fontWeight: FontWeight.w800,
              ),
            ),
    );
  }
}
