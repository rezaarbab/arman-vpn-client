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
      _servers.refresh().then((_) {
        // a list nobody has chosen from yet dials its first row
        if (!_alive || _picked != null || _servers.rows.isEmpty) return;
        final id = _servers.rows.first.id;
        setState(() => _picked = id);
        IrRowsBridge.pick(id).catchError((Object _) {});
      });
    });
  }

  @override
  void dispose() {
    _alive = false;
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
                      title: Text(row.name),
                      subtitle: Text(row.note.isEmpty ? row.id : row.note),
                      trailing: _picked == row.id ? const Icon(Icons.check) : null,
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
