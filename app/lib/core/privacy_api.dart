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
    required this.dropped,
    required this.connected,
    required this.dropWhy,
    required this.dnsOn,
    required this.httpOn,
  });

  final bool on;
  final String chainId;
  final String chainName;

  /// The chosen second hop is no longer in the store (a sync replaced the list).
  final bool chainInStore;

  /// The tunnel took this hop out of its dial because nothing arrived through it. The choice is
  /// still stored, so the page names the server and says it is not being used.
  final bool dropped;

  /// Whether the tunnel is up, which is the only moment a dropped hop is a fact about the running
  /// connection rather than a leftover of one that ended.
  final bool connected;

  /// `same-host` when the two servers are one machine, `carried-nothing` when the hop simply did
  /// not carry, "" when nothing was recorded.
  final String dropWhy;

  /// The two halves of the hardening, switched apart: the core answering lookups, and the open port
  /// being refused. One of them can be the reason a phone suddenly loads nothing.
  final bool dnsOn;
  final bool httpOn;
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

  /// Both rows are the same machine reached on different ports, so the "second hop" is one box
  /// carrying itself - it hides nothing, and the box may refuse to do it at all.
  bool get sameHost => raw['sameHost'] == true;

  /// Whose exit the tunnel answered with, and on which test: `hop2-out` (that server's own egress,
  /// the strongest match), `hop2-dns`, `country2` all mean the chain carries; `hop1-out`,
  /// `hop1-dns`, `country1` mean the hop is in the config and not in use; `other` means neither;
  /// "" means nothing was measured.
  String get exitIs => '${raw['exitIs'] ?? ''}';

  /// The tunnel took the hop out of its dial because nothing arrived through it.
  bool get chainDropped => raw['chainDropped'] == true;

  /// `same-host` / `carried-nothing` / "" - the reason the tunnel recorded with the drop.
  String get dropWhy => '${raw['dropWhy'] ?? ''}';

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
        else if (sameHost)
          'Second hop: that is the same machine as your first server, on another port - nothing is hidden by it'
        else if (chainDropped)
          dropWhy == 'carried-nothing'
              ? 'Second hop: the tunnel dropped it - nothing came through, your choice is kept'
              : 'Second hop: the tunnel dropped it - your choice is kept, ask for it again when you want'
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
        if (exitIs == 'hop2-out')
          'Exit: the second server - the exact address it answers from on its own, so the chain carries'
        else if (exitIs == 'hop2-dns')
          'Exit: the second server, by the address its name resolves to - the chain carries'
        else if (exitIs == 'country2')
          'Exit: the second server, from another of its addresses - the chain is carrying'
        else if (exitIs == 'hop1-out' || exitIs == 'hop1-dns')
          'Exit: the first server - the second hop is in the config and not in use'
        else if (exitIs == 'country1')
          'Exit: the first server, by country - the second hop is not in use'
        else if (exitIs == 'other')
          'Exit: neither server answers as the exit - the chain cannot be confirmed from it',
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
      dropped: v['dropped'] == true,
      connected: v['connected'] == true,
      dropWhy: '${v['dropWhy'] ?? ''}',
      dnsOn: v['dnsOn'] != false,
      httpOn: v['httpOn'] != false,
    );
  }

  static Future<void> set({required bool on, required String chainId}) =>
      _ch.invokeMethod<void>('blobPrivacySet', {'on': on, 'chainId': chainId});

  /// The two halves of the hardening, switched separately; they take effect on the next connect.
  static Future<void> setParts({required bool dns, required bool http}) =>
      _ch.invokeMethod<void>('blobPrivacyParts', {'dns': dns, 'http': http});

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

  /// Whether the phone's current server can carry traffic through [exitId] as its second hop,
  /// tested on a short-lived core so the running tunnel is never disturbed.
  /// 1 carries, 0 does not, -2 the test could not run.
  static Future<int> pairTest(String exitId) async {
    final v = await _ch.invokeMethod<int>('blobPrivacyPair', {'id': exitId});
    return v ?? -2;
  }

  /// The pair verdicts already measured on this phone, by row id.
  static Future<Map<String, String>> pairs() async {
    final m = await _ch.invokeMapMethod<String, String>('blobPrivacyPairs');
    return m ?? const <String, String>{};
  }
}
