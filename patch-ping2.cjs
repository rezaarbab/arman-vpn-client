const fs = require('fs');
const p = 'app/lib/ui/servers_page.dart';
let s = fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n');

s = s.replace(`class _ServersPageState extends State<ServersPage> {
  final _servers = IrServers();
  String? _picked;
  bool _alive = true;`,
`bool _autoSyncedOnce = false;

class _ServersPageState extends State<ServersPage> {
  final _servers = IrServers();
  final _search = TextEditingController();
  String? _picked;
  bool _alive = true;
  Map<String, int> _ping = const {};
  bool _sortPing = false;
  bool _busy = false;`);

s = s.replace(`    _flags?.cancel();`, `    _flags?.cancel();
    _search.dispose();`);

// one exchange per run, then a first ping pass; both awaitable from the buttons too
s = s.replace(`      _loadCached();
      _loadStats();`,
`      _loadCached();
      _loadStats();
      if (!_autoSyncedOnce) {
        _autoSyncedOnce = true;
        _servers.refresh().then((_) {
          if (!_alive) return;
          _loadStats();
          _pingAll();
        });
      }`);

s = s.replace(`  Future<void> _probeNext() async {`,
`  /// A real connect timed in C, a batch at a time so the list fills as it goes.
  Future<void> _pingAll() async {
    if (!_alive) return;
    final ids = [for (final r in _servers.rows.take(120)) r.id];
    for (var i = 0; i < ids.length; i += 25) {
      if (!_alive) return;
      final part = ids.sublist(i, i + 25 > ids.length ? ids.length : i + 25);
      try {
        final m = await IrRowsBridge.ping(part);
        if (!_alive) return;
        setState(() => _ping = {..._ping, ...m});
      } catch (_) {}
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
      _loadCached();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _probeNext() async {`);

// filtered + sorted groups
s = s.replace(`    final groups = _servers.byCategory();`,
`    final q = _search.text.trim().toLowerCase();
    final groups = <String, List<IrServer>>{};
    for (final r in _servers.rows) {
      final cc = (_cc[r.id] ?? '').toLowerCase();
      if (q.isNotEmpty &&
          !r.name.toLowerCase().contains(q) &&
          !(cc.isNotEmpty && cc.contains(q))) {
        continue;
      }
      (groups[r.category.isEmpty ? 'Default' : r.category] ??= []).add(r);
    }
    if (_sortPing) {
      int key(IrServer r) => _ping[r.id] == null || _ping[r.id]! < 0 ? 1 << 30 : _ping[r.id]!;
      for (final e in groups.values) {
        e.sort((a, b) => key(a).compareTo(key(b)));
      }
    }`);

// the header: search, ping all, update, sort
s = s.replace(`        children: [
          Row(
            children: [
              _servers.loading ? const SizedBox.shrink() : const SizedBox.shrink(),
              const Spacer(),`,
`        children: [
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _search,
                  onChanged: (_) => setState(() {}),
                  decoration: const InputDecoration(
                    isDense: true,
                    hintText: 'Search name or country code',
                    prefixIcon: Icon(Icons.search_rounded, size: 18),
                  ),
                ),
              ),
              IconButton(
                tooltip: 'Ping all',
                icon: const Icon(Icons.bolt_rounded),
                onPressed: _pingAll,
              ),
              IconButton(
                tooltip: 'Update the list',
                icon: _busy
                    ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.refresh_rounded),
                onPressed: _busy ? null : _update,
              ),
              IconButton(
                tooltip: 'Sort by ping',
                icon: Icon(_sortPing ? Icons.sort_rounded : Icons.sort_outlined),
                onPressed: () => setState(() => _sortPing = !_sortPing),
              ),
            ],
          ),
          Row(
            children: [
              const Spacer(),`);

// ping beside the row, before the reset/check
s = s.replace(`                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [`,
`                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (_ping[row.id] != null)
                            Padding(
                              padding: const EdgeInsets.only(right: 4),
                              child: Text(
                                _ping[row.id]! < 0 ? '—' : '${_ping[row.id]} ms',
                                style: theme.textTheme.labelSmall?.copyWith(
                                  color: _ping[row.id]! < 0
                                      ? theme.colorScheme.outline
                                      : theme.colorScheme.primary,
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                            ),`);

fs.writeFileSync(p, s);
console.log('ok', ['_pingAll', '_update', '_sortPing', 'irPing'].map((k) => s.split(k).length - 1));
