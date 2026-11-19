# Third-party notices

NoMusic's application code was independently implemented. Embysic and StreamMusic were consulted as functional references. The layout, dark palette, home shortcuts, and bottom player of fn-music-desktop (https://github.com/guihaiwang/fn-music-desktop, reference commit `3a16539916018cd0707a04af81caf1dc91c4b796`) were consulted as visual references. No client source code, icons, screenshots, or artwork from those projects is included in NoMusic.

## Conscrypt 2.5.2

- Source: https://github.com/google/conscrypt/tree/2.5.2
- Maven coordinate: `org.conscrypt:conscrypt-android:2.5.2`
- License: Apache License 2.0.
- Full license is distributed in [conscrypt-apache-2.0.txt](app/src/main/assets/licenses/conscrypt-apache-2.0.txt), also included inside the APK.

Conscrypt includes BoringSSL native libraries. The upstream BoringSSL notices and licenses are distributed in [boringssl-license.txt](app/src/main/assets/licenses/boringssl-license.txt), also included inside the APK. Upstream: https://boringssl.googlesource.com/boringssl/.

## Let's Encrypt public root certificates

The following public CA certificates supplement the Android system trust store. Certificate chain, validity, and hostname verification remain enabled. These files contain public certificates, not private keys.

| Certificate | Official download | SHA-256 fingerprint of DER certificate |
| --- | --- | --- |
| ISRG Root X1 | https://letsencrypt.org/certs/isrgrootx1.pem | `96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6` |
| ISRG Root X2 | https://letsencrypt.org/certs/isrg-root-x2.pem | `69729b8e15a86efc177a57afb7171dfc64add28c2fca8cf1507e34453ccb1470` |

Downloaded from the official sources on 2026-11-13. Certificate documentation: https://letsencrypt.org/certificates/.

## Gradle wrapper

The unmodified Gradle 8.14.3 wrapper scripts and JAR originate from https://github.com/gradle/gradle/tree/v8.14.3/gradle/wrapper and are distributed under Apache License 2.0. The scripts retain their upstream license headers; the full Apache license text is linked above.

## UI assets

The logo, monochrome notification icon, interface icons, and home-card illustrations were drawn for NoMusic using native Android shapes, Canvas paths, and a generated PNG. Album artwork and lyrics are loaded from the user's configured Emby server.
