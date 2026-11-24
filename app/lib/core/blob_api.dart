import 'package:flutter/services.dart';

import 'android_core.dart' show VpnNotAllowed;
import 'core_api.dart';
import 'models.dart';
import 'relay_test.dart';

/// The core through the app's own channel instead of the core's dashboard
/// server: the tunnel, its counters and the store all live in this process.
///
/// The pages read the same models as before. Mozilla's accounts, the browser
/// sign-in, backups and the core's own updater do not exist here; a page that
/// asks for one gets [UnsupportedError] rather than a made-up answer.
class BlobApi implements CoreApi {
  static const _ch = MethodChannel('app/android');

  BlobApi();

  @override
  CoreEndpoint get endpoint =>
      CoreEndpoint(Uri.parse('http://127.0.0.1:10808'), '');

  @override
  void close() {}

  /// The channel hands back maps keyed by `Object?`; the parsers want
  /// `Map<String, Object?>` at every level.
  static Object? _norm(Object? v) {
    if (v is Map) return {for (final e in v.entries) '${e.key}': _norm(e.value)};
    if (v is List) return [for (final e in v) _norm(e)];
    return v;
  }

  static Future<Map<String, Object?>> _call(
    String method, [
    Map<String, Object?>? args,
  ]) async {
    final m = await _ch.invokeMapMethod<Object?, Object?>(method, args);
    final n = _norm(m);
    return n is Map<String, Object?> ? n : const {};
  }

  /// Turns the VPN on or off. It throws [VpnNotAllowed] when the user
  /// refuses the VPN permission Android asks for the first time.
  static Future<void> setOn(bool on) async {
    try {
      await _ch.invokeMethod<void>('blobPower', {'on': on});
    } on PlatformException catch (e) {
      if (e.code == 'vpn_denied') throw const VpnNotAllowed();
      rethrow;
    }
  }

  @override
  Future<CoreStatus> status() async => CoreStatus.fromJson(await _call('blobStatus'));

  /// The store has no Mozilla accounts; the phone says so with an empty list.
  @override
  Future<AccountList> accounts() async =>
      AccountList.fromJson(const {'accounts': <Object?>[]});

  @override
  Future<ConnectionTest> test() async => ConnectionTest.fromJson(await _call('blobTest'));

  @override
  Future<void> delay() async {}

  @override
  Future<void> restartProxy() async {}

  @override
  Future<void> setPower(bool on) => setOn(on);

  @override
  Future<void> autoConnect() => setOn(true);

  /// The home page's Retry. Nothing here: the tunnel is asked for by the
  /// button, the tile, the widget or the notification, and this call arrives
  /// every time the app comes to the front - which is how a disconnect from
  /// the notification turned the VPN back on by itself a second later.
  @override
  Future<void> retry() async {}

  @override
  Future<void> exitCore() => setOn(false);

  @override
  Future<void> openFolder() async {}

  @override
  Future<void> setLocation(String country) async {}

  @override
  Future<void> setSystemProxy(String mode) async {}

  @override
  Future<void> saveSettings(Map<String, Object?> config) async {}

  @override
  Future<Map<String, Object?>> settings() async => const {};

  @override
  Future<List<LogEntry>> logs(int after) async {
    final list =
        await _ch.invokeListMethod<Object?>('blobLogs', {'after': after}) ?? const [];
    return [
      for (final e in list)
        LogEntry.fromJson(switch (_norm(e)) {
          final Map<String, Object?> m => m,
          _ => const {},
        }),
    ];
  }

  /// Servers are the store's rows, not Mozilla's locations.
  @override
  Future<LocationList> locations() async => LocationList.fromJson(const {});

  @override
  Future<LocationList> pingLocations(List<String> codes, {bool fresh = false}) =>
      locations();

  @override
  Future<RoutingInfo> routing() async => RoutingInfo.fromJson(const {});

  @override
  Future<RoutingInfo> updateRoutingLists() => routing();

  @override
  Future<List<ProxyConnection>> connections() async => const <ProxyConnection>[];

  @override
  Future<List<ProxyConnection>> clearConnections() async => const <ProxyConnection>[];

  @override
  Future<List<RunningProgram>> runningPrograms() async => const <RunningProgram>[];

  @override
  Future<List<DnsTestHost>> testDns(List<String> servers) async => const <DnsTestHost>[];

  /// The app carries its flags; the core has none to give.
  @override
  Future<Uint8List?> flag(String code) async => null;

  /// Relay diagnostics are a dashboard endpoint; there is no dashboard here.
  @override
  Stream<RelayTestEvent> testRelay(String url) => const Stream<RelayTestEvent>.empty();

  static Never _unsupported(String what) =>
      throw UnsupportedError('$what is not part of this app');

  @override
  Future<SignInState> startSignIn(String email, String password) async =>
      _unsupported('a Mozilla sign-in');

  @override
  Future<SignInState> startBrowserSignIn(String email, String sessionToken) async =>
      _unsupported('a Mozilla sign-in');

  @override
  Future<String> openSignInProxy({int port = 0}) async =>
      _unsupported('a sign-in proxy');

  @override
  Future<void> closeSignInProxy() async {}

  @override
  Future<SignInState> startSignInAgain(String accountId) async =>
      _unsupported('a Mozilla sign-in');

  @override
  Future<SignInState> signInState(String id) async => _unsupported('a Mozilla sign-in');

  @override
  Future<SignInState> answerSignIn(String id, String value) async =>
      _unsupported('a Mozilla sign-in');

  @override
  Future<void> cancelSignIn(String id) async {}

  @override
  Future<void> activateAccount(String id) async {}

  @override
  Future<void> setAccountEnabled(String id, bool enabled) async {}

  @override
  Future<void> refreshAccount(String id) async {}

  @override
  Future<void> removeAccount(String id) async {}

  @override
  Future<void> setAccountOrder(List<String> ids) async {}

  @override
  Future<Uri> loginLink() async => _unsupported('a Mozilla login');

  @override
  Future<void> downloadUpdate(String version, {Map<String, Object?>? source}) async =>
      _unsupported('the core updater');

  @override
  Future<void> installUpdate(String version, int pid, List<String> args) async =>
      _unsupported('the core updater');

  @override
  Future<DestinationTest> testDestination(String target) async =>
      _unsupported('a destination test');

  @override
  Future<({String name, Uint8List bytes})> exportBackup() async =>
      _unsupported('a core backup');

  @override
  Future<BackupContents> inspectBackup(Uint8List zip) async =>
      _unsupported('a core backup');

  @override
  Future<BackupImported> importBackup(Uint8List zip) async =>
      _unsupported('a core backup');
}
