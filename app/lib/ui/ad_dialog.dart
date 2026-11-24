import 'dart:async';
import 'dart:io';

import 'package:material_ui/material_ui.dart';
import 'package:webview_flutter/webview_flutter.dart' as wf;

import '../core/admin_api.dart';
import '../core/android_core.dart';

/// The panel's advert, shown the way the panel described it: its HTML, its
/// seconds before the close button frees itself, and - if it asked for that -
/// only while the tunnel is up.
///
/// JavaScript is off. An advert is allowed to say something and to hand a link
/// to the browser; it is not allowed to run code on someone's phone, and the
/// panel's HTML is whoever the admin pasted it from.
class AdminAdDialog extends StatefulWidget {
  const AdminAdDialog({super.key, required this.ad});

  final AdminAd ad;

  @override
  State<AdminAdDialog> createState() => _AdminAdDialogState();
}

class _AdminAdDialogState extends State<AdminAdDialog> {
  wf.WebViewController? _view;
  Timer? _tick;
  int _left = 0;

  @override
  void initState() {
    super.initState();
    _left = widget.ad.secs < 0 ? 0 : widget.ad.secs;
    if (_left > 0) {
      _tick = Timer.periodic(const Duration(seconds: 1), (_) {
        if (!mounted) return;
        setState(() {
          _left -= 1;
          if (_left <= 0) _tick?.cancel();
        });
      });
    }
    // The page is loaded as data, never as a URL: nothing is fetched from a
    // server the person holding the phone did not choose.
    final v = wf.WebViewController()
      ..setJavaScriptMode(wf.JavaScriptMode.disabled)
      ..setBackgroundColor(Colors.transparent)
      ..setNavigationDelegate(
        wf.NavigationDelegate(
          onNavigationRequest: (req) {
            final scheme = Uri.tryParse(req.url)?.scheme ?? '';
            // The link belongs to the browser, not to this dialog: an advert
            // that keeps a phone inside itself is not a message, it is a cage.
            if (scheme == 'http' || scheme == 'https') AndroidCore.openUrl(req.url);
            return wf.NavigationDecision.prevent;
          },
        ),
      )
      ..loadHtmlString(_page(widget.ad.html), baseUrl: null);
    _view = v;
  }

  /// The admin's HTML inside a page that matches the app, so a plain paragraph
  /// does not arrive black-on-black in a dark theme.
  String _page(String body) => '<!doctype html><html><head><meta charset="utf-8">'
      '<meta name="viewport" content="width=device-width,initial-scale=1">'
      '<style>body{margin:0;padding:2px;font:15px/1.7 system-ui,sans-serif;'
      '-webkit-text-size-adjust:100%;word-break:break-word}'
      'img{max-width:100%;height:auto;border-radius:8px}'
      'a{color:#2f6fdb}</style></head><body>$body</body></html>';

  @override
  void dispose() {
    _tick?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final view = _view;
    return AlertDialog(
      contentPadding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
      content: SizedBox(
        width: 420,
        height: 360,
        child: view == null
            ? const Center(child: CircularProgressIndicator())
            : wf.WebViewWidget(controller: view),
      ),
      actions: [
        TextButton(
          onPressed: _left > 0 ? null : () => Navigator.pop(context),
          child: Text(_left > 0 ? 'Close ($_left)' : 'Close'),
        ),
      ],
    );
  }
}

/// Shows the advert once per app run per id, and only when the panel's own
/// condition is met; the receipt goes back so the counter is honest.
///
/// It asks the phone for the advert itself, because the app is never told when
/// a sync finished: the panel's advert arrives inside one, and the moment to
/// notice it is the next time this page is stood on or the tunnel changes.
class AdGate extends StatefulWidget {
  const AdGate({super.key, required this.connected, required this.stand});

  final bool connected;

  /// Whatever changes when the person moves around the app - a reason to look
  /// again, since nothing announces a finished sync.
  final Object? stand;

  @override
  State<AdGate> createState() => _AdGateState();
}

class _AdGateState extends State<AdGate> {
  /// Shown ids for this process. A person who closed an advert must not be
  /// handed it again because they opened Settings and came back.
  static final _shown = <String>{};

  AdminAd? _ad;
  bool _asking = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _maybe());
  }

  @override
  void didUpdateWidget(AdGate old) {
    super.didUpdateWidget(old);
    if (widget.connected != old.connected || widget.stand != old.stand) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _maybe());
    }
  }

  Future<void> _maybe() async {
    if (!mounted) return;
    if (_ad == null && !_asking) {
      _asking = true;
      try {
        final bundle = await AdminApi.load();
        _ad = bundle.ad;
      } catch (_) {
        // no channel, no advert: a phone with no gate is a phone with nothing
        // to say, and the page must not care
      }
      _asking = false;
    }
    _try();
  }

  void _try() {
    final ad = _ad;
    if (!mounted || ad == null || !ad.worthShowing) return;
    if (_shown.contains(ad.id)) return;
    // The Android web view only. The computer's copy uses a different plugin,
    // and an advert is not worth a second renderer.
    if (!Platform.isAndroid) return;
    // "after the VPN" means exactly that: a phone that is not connected is
    // left alone, and the panel's counter is not spent on a show it refused.
    if (ad.after && !widget.connected) return;
    _shown.add(ad.id);
    showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => AdminAdDialog(ad: ad),
    ).then((_) => AdminApi.seen('ad', ad.id));
  }

  @override
  Widget build(BuildContext context) => const SizedBox.shrink();
}
