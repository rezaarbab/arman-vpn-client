import 'dart:io';

import 'package:material_ui/material_ui.dart';

import '../app_info.dart';
import '../app_settings.dart';
import '../core/core_controller.dart';
import '../l10n/app_localizations.dart';
import '../platform.dart';
import 'bypass_apps_page.dart';
import 'settings_page.dart';
import 'widgets.dart';

/// The versions, and the places the app's files are.
///
/// There is no update check here on purpose. A phone's build came from wherever
/// its user got the APK, and the only release feed this code knew was another
/// project's - which could have handed a user someone else's file as "the new
/// version". The build's own commit is in the log page instead.
///
/// Routing lives here too, on a phone: which apps go outside the tunnel and
/// where the traffic is decided is something a person reads about the app, not
/// a knob they turn every day, and General had filled up with it.
class AboutPage extends StatelessWidget {
  const AboutPage({super.key, required this.core, required this.settings});

  final CoreController core;
  final AppSettings settings;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final status = core.status;
    final muted = theme.textTheme.bodySmall?.copyWith(
      color: theme.colorScheme.onSurfaceVariant,
    );
    return ListView(
      padding: pagePadding(context),
      children: [
        PageTitle(l.navAbout),
        const SizedBox(height: 20),
        Panel(
          child: Column(
            children: [
              const AppLogo(size: 72),
              const SizedBox(height: 10),
              Text(l.appTitle, style: theme.textTheme.titleLarge),
              const SizedBox(height: 4),
              Text(l.unofficialNote, style: muted, textAlign: TextAlign.center),
              const SizedBox(height: 20),
              _Line(label: l.appVersionLabel, value: appVersion),
              const Divider(height: 24),
              _Line(label: l.coreVersionLabel, value: status?.version ?? '—'),
              if (Platform.isAndroid) ...[
                const Divider(height: 24),
                // Where the traffic is decided. Two pages, because they answer
                // two different questions: which apps skip the tunnel, and
                // what the tunnel does with the rest.
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  leading: const Icon(Icons.alt_route_rounded),
                  title: Text(l.bypassApps),
                  trailing: const Icon(Icons.chevron_right_rounded),
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(builder: (_) => const BypassAppsPage()),
                  ),
                ),
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  leading: const Icon(Icons.public_rounded),
                  title: Text(l.sectionRouting),
                  trailing: const Icon(Icons.chevron_right_rounded),
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => SettingsPage(
                        core: core,
                        settings: settings,
                        section: SettingsSection.routing,
                      ),
                    ),
                  ),
                ),
                const Divider(height: 24),
                Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            l.webDashboard,
                            style: theme.textTheme.titleSmall,
                          ),
                          const SizedBox(height: 2),
                          Text(l.webDashboardHint, style: muted),
                        ],
                      ),
                    ),
                    OutlinedButton.icon(
                      onPressed: () => runShowingErrors(
                        context,
                        () async => openUrl('${await core.dashboardLink()}'),
                      ),
                      icon: const Icon(Icons.open_in_new_rounded, size: 18),
                      label: Text(l.webDashboardOpen),
                    ),
                  ],
                ),
              ],
              // The data folder: not on a phone, where it is the app's
              // private storage, which nobody can open.
              if (!Platform.isAndroid &&
                  status != null &&
                  status.dataDir.isNotEmpty) ...[
                const Divider(height: 24),
                Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(l.dataFolder, style: theme.textTheme.titleSmall),
                          const SizedBox(height: 2),
                          Text(
                            status.dataDir,
                            style: muted,
                            textDirection: TextDirection.ltr,
                          ),
                        ],
                      ),
                    ),
                    OutlinedButton.icon(
                      onPressed: () =>
                          runShowingErrors(context, core.openFolder),
                      icon: const Icon(Icons.folder_open_rounded, size: 18),
                      label: Text(l.openFolder),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _Line extends StatelessWidget {
  const _Line({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      children: [
        Expanded(child: Text(label, style: theme.textTheme.titleSmall)),
        Text(
          value,
          style: theme.textTheme.bodyMedium,
          textDirection: TextDirection.ltr,
        ),
      ],
    );
  }
}
