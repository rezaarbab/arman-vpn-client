import 'package:flutter/services.dart';

/// The phone's own privacy switches, and what the last check measured.
///
/// The config the core runs on is assembled at connect time, so a change here takes
/// effect on the next connect - the page says so instead of pretending it is live.
class PrivacyState {
  const PrivacyState({
    required this.on,
    required this.chainId,
    required this.chainName,
    required this.chainInStore,
  });

  final bool on;
  final String chainId;
  final String chainName;

  /// The chosen second hop is no longer in the store (a sync replaced the list).
  final bool chainInStore;
}

class PrivacyChecks {
  const PrivacyChecks(this.raw);

  final Map<Object?, Object?> raw;

  bool get hardening => raw['hardening'] == true;
  bool get chainChosen => raw['chainChosen'] == true;
  bool get chainInStore => raw['chainInStore'] == true;
  bool get chainCarries => raw['chainCarries'] == true;
  bool get exitKnown => raw['exitKnown'] == true;
  bool get connected => raw['connected'] == true;
  bool get tunnelSilent => raw['tunnelSilent'] == true;

  /// A page on other infrastructure answered through the tunnel while the trace page refused it:
  /// the tunnel carries and the refusal belongs to one host, so no verdict may be drawn from it.
  bool get pageRefuses => raw['pageRefuses'] == true;

  /// The pages refuse the phone itself, tunnel or not: there is nothing here to measure.
  bool get notMeasurable => raw['notMeasurable'] == true;

  /// Whose address the tunnel exits with: `hop2` means the chain carries, `hop1` means the second
  /// hop is in the config and not in use, `other` means neither, and "" means nothing was measured.
  String get exitIs => '${raw['exitIs'] ?? ''}';

  /// `hardening-chain-chosen`, as the library wrote it for the tunnel that is running.
  String get running => '${raw['running'] ?? ''}';

  /// The switch says one thing, the running config says another: the change is
  /// sitting in the settings and has not been dialed with yet.
  bool get staleHardening =>
      hardening && running.isNotEmpty && !running.startsWith('1');
  bool get staleChain =>
      chainChosen && connected && running.split('-').length == 3 && running.split('-')[1] != '1';
  bool? get httpBlocked => raw['httpBlocked'] == null ? null : raw['httpBlocked'] == true;

  /// The lines, each a fact the network or the running config answered.
  List<String> lines() => [
        hardening
            ? 'Hardening: on - encrypted lookups and a refused port 80, in the config that was built'
            : 'Hardening: off',
        if (running.isEmpty)
          'Running config: nothing has been dialed with these settings yet'
        else
          'Running config: hardening ${running.split("-")[0] == "1" ? "in it" : "NOT in it"}'
              ' - second hop ${running.split("-").length > 1 && running.split("-")[1] == "1" ? "in it" : "NOT in it"}',
        if (staleHardening || staleChain)
          'The tunnel was dialed before this change - turn it off and on again',
        if (!chainChosen)
          'Second hop: not chosen - one server sees every site you open'
        else if (!chainInStore)
          'Second hop: that server is no longer in the list - pick it again'
        else if (!connected)
          'Second hop: connect first - nothing can be measured while it is off'
        else if (notMeasurable)
          'Second hop: the pages refuse this network from both sides - nothing can be measured now'
        else if (tunnelSilent)
          'Second hop: nothing arrives through the tunnel - it is not carrying traffic'
        else if (pageRefuses)
          chainCarries
              ? 'Second hop: the traffic leaves through it, on the answer of a page the first one refused'
              : 'Second hop: NOT carrying - the exit is still the first server'
        else
          chainCarries
              ? 'Second hop: the traffic leaves through it - the first server sees one destination'
              : 'Second hop: NOT carrying - the traffic still exits through the first server',
        if (exitIs == 'hop2')
          'Exit: the second server - the chain is carrying'
        else if (exitIs == 'hop1')
          'Exit: the first server - the second hop is in the config and not in use'
        else if (exitIs == 'other')
          'Exit: neither resolved address - the chain cannot be confirmed from it',
        if (httpBlocked != null)
          httpBlocked!
              ? 'Plain HTTP: refused by the tunnel'
              : 'Plain HTTP: still gets through the tunnel',
      ];
}

class PrivacyApi {
  PrivacyApi._();

  static const _ch = MethodChannel('app/android');

  static Future<PrivacyState> get() async {
    final m = await _ch.invokeMethod<Map<Object?, Object?>?>('blobPrivacy');
    final v = m ?? const <Object?, Object?>{};
    return PrivacyState(
      on: v['on'] == true,
      chainId: '${v['chainId'] ?? ''}',
      chainName: '${v['chainName'] ?? ''}',
      chainInStore: v['chainInStore'] == true,
    );
  }

  static Future<void> set({required bool on, required String chainId}) =>
      _ch.invokeMethod<void>('blobPrivacySet', {'on': on, 'chainId': chainId});

  /// Runs the checks on the phone's own threads; the answers come from the network.
  static Future<PrivacyChecks> test() async {
    final m = await _ch.invokeMethod<Map<Object?, Object?>?>('blobPrivacyTest');
    return PrivacyChecks(m ?? const <Object?, Object?>{});
  }

  /// Every question the network can answer, as lines: the page through the tunnel, the
  /// page outside it, each hop measured alone, and what the running config was built with.
  static Future<List<String>> debug() async {
    final l = await _ch.invokeListMethod<String>('blobPrivacyDebug');
    return l ?? const [];
  }
}
