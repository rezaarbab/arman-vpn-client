const fs = require('fs');
function edit(p, fn) { let s = fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n'); s = fn(s); fs.writeFileSync(p, s); }
function replaceAll(p, a, b) { edit(p, (s) => s.split(a).join(b)); }

// 4) no trace of the old name
for (const p of ['app/lib/l10n/app_en.arb', 'app/lib/l10n/app_fa.arb',
                 'app/lib/l10n/app_localizations_en.dart', 'app/lib/l10n/app_localizations_fa.dart',
                 'app/lib/l10n/app_localizations.dart']) {
  replaceAll(p, 'Arman VPN', 'Blob VPN');
}

// 3) the row is called what it opens
edit('app/lib/ui/mobile_shell.dart', (s) => s.replace(
  '() => push(l.sectionRouting, () => BypassAppsPage()),',
  "() => push('Per-app', () => BypassAppsPage()),"));

// 1) pings survive a visit; only the button measures
edit('app/android/app/src/main/kotlin/com/arman/firefoxvpn/Flags.kt', (s) => s
  .replace(`            if (loc.length == 2) {
                val ms = ((System.nanoTime() - t0) / 1000000L).toInt()`,
`            if (loc.length == 2) {
                val raw = ((System.nanoTime() - t0) / 1000000L).toInt()
                // a round trip through a remote server is never 1 ms: that is a local
                // answer and would paint a dead row green
                val ms = if (raw < 10) -1 else raw`)
  .replace('    fun probe(context: Context, id: String): String {',
`    /** Everything already measured, id -> ms. */
    fun allDelays(context: Context): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((k, v) in context.getSharedPreferences("blob_core", Context.MODE_PRIVATE).all) {
            if (k.startsWith("pd_") && v is Int && v > 0) out[k.removePrefix("pd_")] = v
        }
        return out
    }

    /** The picked row's own answers: delay, exit country and exit IP. */
    fun info(context: Context, id: String): Map<String, Any?> {
        val p = context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)
        val out = HashMap<String, Any?>()
        out["ms"] = delay(context, id)
        out["cc"] = p.getString("cc_" + id.take(64), "") ?: ""
        out["ip"] = p.getString("pi_" + id.take(64), "") ?: ""
        if (CoreService.on || id.isBlank()) return out
        val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
        if (row < 0) return out
        val port = 20000 + Random.nextInt(2000)
        val quiet = object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(p0: Long, p1: String?): Long = 0L
        }
        val handle = runCatching { NativeSec.probeStart(row, port, quiet) }.getOrDefault(0)
        if (handle == 0) return out
        try {
            val trace = NativeSec.probe("https://www.cloudflare.com/cdn-cgi/trace", "loc,ip", port)
            var cc = ""
            var ip = ""
            for (line in trace.split('\\n')) {
                if (line.startsWith("loc=")) cc = line.removePrefix("loc=").trim().uppercase()
                if (line.startsWith("ip=")) ip = line.removePrefix("ip=").trim()
            }
            if (cc.length == 2) {
                p.edit().putString("cc_" + id.take(64), cc).apply()
                out["cc"] = cc
            }
            if (ip.length in 7..45) {
                p.edit().putString("pi_" + id.take(64), ip).apply()
                out["ip"] = ip
            }
        } finally {
            runCatching { NativeSec.probeStop(handle) }
        }
        return out
    }

    fun probe(context: Context, id: String): String {`));

edit('app/android/app/src/main/kotlin/com/arman/firefoxvpn/MainActivity.kt', (s) => s
  .replace('                "irPickPing" -> result.success(CoreService.lastDelay(this, CoreService.picked(this)))',
`                "irPickPing" -> result.success(CoreService.lastDelay(this, CoreService.picked(this)))
                "irPingsAll" -> result.success(Flags.allDelays(this))
                "irPickInfo" -> inBackground(result) {
                    Flags.info(this, CoreService.picked(this))
                }`));

edit('app/lib/core/ir_bridge.dart', (s) => s.replace(
  "  static Future<int> pickPing() async =>",
`  static Future<Map<String, int>> pingsAll() async {
    final m = await _ch.invokeMethod<Map>('irPingsAll');
    return {
      for (final e in (m ?? {}).entries)
        '${e.key}': e.value is int ? e.value as int : int.tryParse('${e.value}') ?? -1,
    };
  }

  static Future<Map<String, String>> pickInfo() async {
    final m = await _ch.invokeMethod<Map>('irPickInfo');
    return {for (final e in (m ?? {}).entries) '${e.key}': '${e.value}'};
  }

  static Future<int> pickPing() async =>`));

// 1+6) no ping on entry, pings from the cache, and the search icon next to the others
edit('app/lib/ui/servers_page.dart', (s) => s
  .replace(`      _pingAll();\n`, '')
  .replace(`      _loadCached();
      _loadStats();`, `      _loadCached();
      _loadStats();
      _loadPings();`)
  .replace(`  /// What an earlier visit already learned: flags and sessions show at once,`,
`  Future<void> _loadPings() async {
    try {
      final m = await IrRowsBridge.pingsAll();
      if (_alive && m.isNotEmpty) setState(() => _ping = {...m, ..._ping});
    } catch (_) {}
  }

  /// What an earlier visit already learned: flags and sessions show at once,`)
  .replace(`              else ...[
                IconButton(
                  tooltip: 'Search',`, `              else ...[
                IconButton(
                  tooltip: 'Search',`));

// 2+5) Home: the picked row's exit IP, its country and a fresh ping
edit('app/lib/ui/overview_page.dart', (s) => s
  .replace(`      final cc = await IrRowsBridge.flagOf();
      final ms = await IrRowsBridge.pickPing();
      if (mounted && _for == id) {
        setState(() {
          if (cc.length == 2) _cc = cc.toLowerCase();
          _ping = ms;
        });
      }`, `      final info = await IrRowsBridge.pickInfo();
      if (mounted && _for == id) {
        setState(() {
          final cc = (info['cc'] ?? '').toLowerCase();
          if (cc.length == 2) _cc = cc;
          final ms = int.tryParse(info['ms'] ?? '') ?? -1;
          _ping = ms < 10 ? -1 : ms;
          _ip = info['ip'] ?? '';
        });
      }`)
  .replace('  int _ping = -1;', '  int _ping = -1;\n  String _ip = \'\';')
  .replace(`          IconButton(
            tooltip: 'Refresh',`, `          if (_ip.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(right: 6),
              child: Text(
                _ip,
                style: theme.textTheme.labelSmall?.copyWith(color: p.muted),
              ),
            ),
          IconButton(
            tooltip: 'Refresh',`));
console.log('ok');
