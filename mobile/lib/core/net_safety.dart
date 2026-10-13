import 'dart:async';
import 'dart:io';

/// True only for globally routable addresses. Private, loopback,
/// link-local, multicast and reserved ranges are rejected.
bool isPublicAddress(InternetAddress address) {
  final b = address.rawAddress;
  if (address.type == InternetAddressType.IPv4) return _publicV4(b);
  if (address.isLoopback || address.isLinkLocal || address.isMulticast) {
    return false;
  }
  if (b.every((x) => x == 0)) return false; // ::
  if (_isMappedV4(b)) return _publicV4(b.sublist(12));
  if ((b[0] & 0xfe) == 0xfc) return false; // fc00::/7 unique-local
  if (b[0] == 0xfe && (b[1] & 0xc0) == 0x80) return false; // fe80::/10
  if (b[0] == 0xfe && (b[1] & 0xc0) == 0xc0) return false; // fec0::/10
  if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) {
    return false; // documentation
  }
  return true;
}

bool _isMappedV4(List<int> b) {
  for (var i = 0; i < 10; i++) {
    if (b[i] != 0) return false;
  }
  return b[10] == 0xff && b[11] == 0xff;
}

bool _publicV4(List<int> b) {
  final a = b[0];
  final c = b[1];
  if (a == 0 || a == 10 || a == 127) return false;
  if (a == 100 && (c & 0xc0) == 64) return false; // 100.64/10 CGNAT
  if (a == 169 && c == 254) return false; // link-local
  if (a == 172 && (c & 0xf0) == 16) return false; // 172.16/12
  if (a == 192 && c == 168) return false; // 192.168/16
  if (a == 192 && c == 0) return false; // IETF / documentation blocks
  if (a == 198 && (c == 18 || c == 19)) return false; // benchmarking
  if (a >= 224) return false; // multicast and reserved
  return true;
}

/// Resolves the host and checks every returned address.
Future<bool> isPublicUrl(Uri uri) async {
  if (uri.scheme != 'http' && uri.scheme != 'https') return false;
  if (uri.host.isEmpty) return false;
  final literal = InternetAddress.tryParse(uri.host);
  if (literal != null) return isPublicAddress(literal);
  try {
    final addresses = await InternetAddress.lookup(uri.host)
        .timeout(const Duration(seconds: 8));
    if (addresses.isEmpty) return false;
    return addresses.every(isPublicAddress);
  } catch (_) {
    return false;
  }
}
