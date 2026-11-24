import 'package:flutter/services.dart';

/// What the panel's "messages and update" section said about this handset.
///
/// The text is the admin's, kept by the phone until the person has answered
/// it, and it never travels through the log: a log line is read by whoever
/// plugs the phone in, not only by the one holding it.
class AdminMessage {
  const AdminMessage({
    required this.id,
    required this.title,
    required this.body,
    required this.btnText,
    required this.btnUrl,
    required this.btnEnabled,
    required this.cancelEnabled,
    required this.left,
  });

  final String id;
  final String title;
  final String body;
  final String btnText;
  final String btnUrl;

  /// The panel can take the button away - a message with no way out is the
  /// admin's choice, not the app's.
  final bool btnEnabled;
  final bool cancelEnabled;

  /// How many more shows the worker will still hand over. The app does not
  /// count them: the panel paces its own message per handset.
  final int left;

  bool get worthShowing => title.isNotEmpty || body.isNotEmpty;

  static AdminMessage? fromMap(Map<Object?, Object?>? m) {
    if (m == null || m.isEmpty) return null;
    final id = '${m['id'] ?? ''}';
    if (id.isEmpty) return null;
    return AdminMessage(
      id: id,
      title: '${m['title'] ?? ''}',
      body: '${m['body'] ?? ''}',
      btnText: '${m['btnText'] ?? ''}',
      btnUrl: '${m['btnUrl'] ?? ''}',
      btnEnabled: m['btnEnabled'] != false,
      cancelEnabled: m['cancelEnabled'] != false,
      left: (m['left'] as num?)?.toInt() ?? 1,
    );
  }
}

class AdminUpdate {
  const AdminUpdate({
    required this.forced,
    required this.text,
    required this.btnText,
    required this.url,
  });

  final bool forced;
  final String text;
  final String btnText;
  final String url;

  /// Forced with no link is a welded door, so the app never treats it as one:
  /// the dialog shows, but nothing is refused.
  bool get blocks => forced && url.isNotEmpty;

  static AdminUpdate? fromMap(Map<Object?, Object?>? m) {
    if (m == null || m.isEmpty) return null;
    return AdminUpdate(
      forced: m['forced'] == true,
      text: '${m['text'] ?? ''}',
      btnText: '${m['btnText'] ?? 'Update'}',
      url: '${m['url'] ?? ''}',
    );
  }
}

/// The panel's messages, and the receipt the worker waits for.
class AdminApi {
  AdminApi._();

  static const _ch = MethodChannel('app/android');

  static Future<(AdminMessage?, AdminUpdate?)> load() async {
    final m = await _ch.invokeMethod<Map<Object?, Object?>?>('irAdmin');
    final map = m ?? const <Object?, Object?>{};
    return (
      AdminMessage.fromMap(map['popup'] as Map<Object?, Object?>?),
      AdminUpdate.fromMap(map['update'] as Map<Object?, Object?>?),
    );
  }

  /// "seen": the panel counts devices that answered, and stops sending.
  static Future<void> seen(String kind, String id) async {
    if (id.isEmpty) return;
    await _ch.invokeMethod<void>('irAdminSeen', {'kind': kind, 'id': id});
  }
}
