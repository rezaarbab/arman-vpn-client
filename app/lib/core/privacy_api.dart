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
  bool? get httpBlocked => raw['httpBlocked'] == null ? null : raw['httpBlocked'] == true;

  /// Three lines, each a fact the network answered, not a setting echoed back.
  List<String> lines() => [
        hardening
            ? 'Hardening: on - lookups go out encrypted, plain web traffic is refused'
            : 'Hardening: off',
        if (!chainChosen)
          'Second hop: not chosen - one server sees every site you open'
        else if (!chainInStore)
          'Second hop: that server is no longer in the list - pick it again'
        else if (!connected)
          'Second hop: connect first - there is no exit to measure while it is off'
        else if (!exitKnown)
          'Second hop: the exit could not be measured - the page did not answer'
        else
          chainCarries
              ? 'Second hop: the traffic leaves through it - the first server sees one destination'
              : 'Second hop: NOT carrying - the traffic still exits through the first server',
        if (httpBlocked != null)
          httpBlocked!
              ? 'Plain HTTP: refused'
              : 'Plain HTTP: still gets through',
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
}
