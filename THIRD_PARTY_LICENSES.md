# Third-party licenses

Vigilo is licensed under the [MIT License](LICENSE). This document lists the third-party
software and data redistributed or used by the Home Assistant add-on and the Android app,
with the license taken from each project's own LICENSE/NOTICE files (not guessed).

The same file is shipped in the add-on image at `/usr/share/licenses/vigilo/`.
A copy of the Apache License 2.0 is in [`addon/vigilo/licenses/Apache-2.0.txt`](addon/vigilo/licenses/Apache-2.0.txt).

## Add-on — direct dependencies and bundled software

| Component | Version | License | Source |
|---|---|---|---|
| teslamotors/fleet-telemetry (official binary, image `tesla/fleet-telemetry`) | v0.10.0 | Apache-2.0 | https://github.com/teslamotors/fleet-telemetry |
| teslamotors/vehicle-command | v0.4.1 | Apache-2.0 | https://github.com/teslamotors/vehicle-command |
| Tailscale `tsnet` (`tailscale.com`) | v1.104.7 | BSD-3-Clause | https://github.com/tailscale/tailscale |
| Eclipse Paho MQTT Golang (`paho.mqtt.golang`) | v1.5.1 | EPL-2.0 OR EDL-1.0 (BSD-3-Clause) | https://github.com/eclipse/paho.mqtt.golang |
| golang-jwt/jwt | v5.2.2 | MIT | https://github.com/golang-jwt/jwt |
| skip2/go-qrcode | 2020-06-17 | MIT | https://github.com/skip2/go-qrcode |
| Home Assistant `base-debian` (`ghcr.io/home-assistant/base-debian:bookworm`) | bookworm | Apache-2.0 (image sources) | https://github.com/home-assistant/docker-base |
| s6-overlay (init in the HA base image) | (as shipped by the base image) | ISC | https://github.com/just-containers/s6-overlay |

The Home Assistant base image is built on Debian. Debian package licenses are shipped
inside the image under `/usr/share/doc/*/copyright`.

`fleet-telemetry` v0.10.0 and `vehicle-command` v0.4.1 do **not** include a `NOTICE` file
in their upstream trees. Apache-2.0 §4(d) therefore does not require redistributing a
NOTICE from those projects. Attribution and a copy of the Apache License 2.0 are included
below and in the image.

## Add-on — Go modules pulled in transitively (mostly by Tailscale)

Licenses read from each module's LICENSE file in the Go module cache, matching
`addon/vigilo/server/go.mod`.

| Module | License |
|---|---|
| filippo.io/edwards25519 | BSD-3-Clause |
| github.com/akutz/memconn | Apache-2.0 |
| github.com/alexbrainman/sspi | BSD-3-Clause |
| github.com/coder/websocket | ISC |
| github.com/creachadair/msync | BSD-2-Clause |
| github.com/cronokirby/saferith | MIT |
| github.com/dblohm7/wingoes | BSD-3-Clause |
| github.com/fxamacker/cbor/v2 | MIT |
| github.com/gaissmai/bart | MIT |
| github.com/go-json-experiment/json | BSD-3-Clause |
| github.com/godbus/dbus/v5 | BSD-2-Clause |
| github.com/golang/groupcache | Apache-2.0 |
| github.com/google/btree | Apache-2.0 |
| github.com/google/go-cmp | BSD-3-Clause |
| github.com/gorilla/websocket | BSD-2-Clause |
| github.com/hdevalence/ed25519consensus | BSD-3-Clause |
| github.com/huin/goupnp | BSD-2-Clause |
| github.com/jsimonetti/rtnetlink | MIT |
| github.com/klauspost/compress | Apache-2.0 |
| github.com/mdlayher/netlink | MIT |
| github.com/mdlayher/socket | MIT |
| github.com/mitchellh/go-ps | MIT |
| github.com/pires/go-proxyproto | Apache-2.0 |
| github.com/safchain/ethtool | Apache-2.0 |
| github.com/tailscale/certstore | MIT |
| github.com/tailscale/go-winio | MIT |
| github.com/tailscale/hujson | BSD-3-Clause |
| github.com/tailscale/peercred | BSD-3-Clause |
| github.com/tailscale/web-client-prebuilt | BSD-3-Clause |
| github.com/tailscale/wireguard-go | MIT |
| github.com/x448/float16 | MIT |
| go4.org/mem | Apache-2.0 |
| go4.org/netipx | BSD-3-Clause |
| golang.org/x/crypto, exp, net, oauth2, sync, sys, term, text, time | BSD-3-Clause |
| golang.zx2c4.com/wintun | MIT |
| golang.zx2c4.com/wireguard/windows | MIT |
| google.golang.org/protobuf | BSD-3-Clause |
| gvisor.dev/gvisor | Apache-2.0 |

## Android app

| Component | Version | License | Source |
|---|---|---|---|
| AndroidX (Compose, Material 3, Activity, Lifecycle, Core, Room, WorkManager, Security Crypto, ExifInterface, Glance, Fragment) | as in `app/build.gradle.kts` | Apache-2.0 | https://developer.android.com/jetpack |
| Kotlin, kotlinx-coroutines, kotlinx-serialization-json | as in `app/build.gradle.kts` | Apache-2.0 | https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines, https://github.com/Kotlin/kotlinx.serialization |
| osmdroid | 6.1.20 | Apache-2.0 | https://github.com/osmdroid/osmdroid |
| OpenStreetMap data and standard tile layer (`TileSourceFactory.MAPNIK`) | — | ODbL 1.0; © OpenStreetMap contributors | https://www.openstreetmap.org/copyright |
| OkHttp | 4.12.0 | Apache-2.0 | https://github.com/square/okhttp |
| Bouncy Castle (`bcprov-jdk18on`, `bctls-jdk18on`) | 1.80 | MIT | https://www.bouncycastle.org/licence.html |
| Google Play Services Code Scanner | 16.1.0 | Google Play Services / Android SDK terms (not an OSI open-source license) | https://developers.google.com/android/guides/licenses |
| JUnit (tests only) | 4.13.2 | EPL-1.0 | https://junit.org/junit4/ |
| Optional car photo: Tesla Model 3 Highland Pearl White Multi-Coat by Ethan Llamas, Wikimedia Commons | — | CC BY-SA 4.0 | https://commons.wikimedia.org/wiki/File:Tesla_Model_3_Highland_Performance_AWD_Pearl_White_Multi-Coat.jpg |

Build-only tools (KSP, Paparazzi, Android Gradle Plugin) are not shipped in the APK.

---

## NOTICE — Apache License 2.0 components

This product redistributes the following works under the Apache License, Version 2.0.
A complete copy of the license is in `addon/vigilo/licenses/Apache-2.0.txt`.
You may also obtain it at http://www.apache.org/licenses/LICENSE-2.0

**teslamotors/fleet-telemetry v0.10.0**
https://github.com/teslamotors/fleet-telemetry
Official server binary taken from the image `tesla/fleet-telemetry:v0.10.0`.
The upstream v0.10.0 source tree does not include a NOTICE file.

**teslamotors/vehicle-command v0.4.1**
https://github.com/teslamotors/vehicle-command
The upstream v0.4.1 module does not include a NOTICE file.

Other Apache-2.0 components used by Vigilo (AndroidX, Kotlin and kotlinx libraries,
osmdroid, OkHttp, Home Assistant docker-base, and the Apache-2.0 Go modules listed above)
are licensed under the same Apache License 2.0. Those projects either do not ship a NOTICE
file, or their NOTICE (if any) is available in their upstream repositories at the URLs
given in the tables.

---

## Notices for paho.mqtt.golang

The Eclipse Paho project requires redistribution of the following NOTICE
(from https://github.com/eclipse/paho.mqtt.golang, v1.5.1):

```
# Notices for paho.mqtt.golang

This content is produced and maintained by the Eclipse Paho project.

 * Project home: https://www.eclipse.org/paho/

Note that a [separate mqtt v5 client](https://github.com/eclipse/paho.golang) also exists (this is a full rewrite
and deliberately incompatible with this library).

## Trademarks

Eclipse Mosquitto trademarks of the Eclipse Foundation. Eclipse, and the
Eclipse Logo are registered trademarks of the Eclipse Foundation.

Paho is a trademark of the Eclipse Foundation. Eclipse, and the Eclipse Logo are
registered trademarks of the Eclipse Foundation.

## Copyright

All content is the property of the respective authors or their employers.
For more information regarding authorship of content, please consult the
listed source code repository logs.

## Declared Project Licenses

This program and the accompanying materials are made available under the terms of the 
Eclipse Public License v2.0 and Eclipse Distribution License v1.0 which accompany this
distribution.

The Eclipse Public License is available at
https://www.eclipse.org/legal/epl-2.0/
and the Eclipse Distribution License is available at
http://www.eclipse.org/org/documents/edl-v10.php.

For an explanation of what dual-licensing means to you, see:
https://www.eclipse.org/legal/eplfaq.php#DUALLIC

SPDX-License-Identifier: EPL-2.0 or BSD-3-Clause

## Source Code

The project maintains the following source code repositories:

 * https://github.com/eclipse/paho.mqtt.golang

## Third-party Content

This project makes use of the follow third party projects.

Go Programming Language and Standard Library

* License: BSD-style license (https://golang.org/LICENSE)
* Project: https://golang.org/

Go Networking

* License: BSD 3-Clause style license and patent grant.
* Project: https://cs.opensource.google/go/x/net

Go Sync

* License: BSD 3-Clause style license and patent grant.
* Project: https://cs.opensource.google/go/x/sync/

Gorilla Websockets v1.4.2

* License: BSD 2-Clause "Simplified" License
* Project: https://github.com/gorilla/websocket

## Cryptography

Content may contain encryption software. The country in which you are currently
may have restrictions on the import, possession, and use, and/or re-export to
another country, of encryption software. BEFORE using any encryption software,
please check the country's laws, regulations and policies concerning the import,
possession, or use, and re-export of encryption software, to see if this is
permitted.
```

---

## Copyright notices (short licenses)

The following copyright notices are reproduced as required by the MIT, BSD and ISC licenses
of the direct dependencies.

### Tailscale tsnet (BSD-3-Clause)

Copyright (c) 2020 Tailscale Inc & contributors.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

### golang-jwt/jwt (MIT)

Copyright (c) 2012 Dave Grijalva
Copyright (c) 2021 golang-jwt maintainers

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### skip2/go-qrcode (MIT)

Copyright (c) 2014 Tom Harwood

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

### Bouncy Castle (MIT)

Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org).

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions: The above copyright notice and this
permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### s6-overlay (ISC)

Copyright (c) 2021-2026 Laurent Bercot <ska-skaware@skarnet.org>, John Regan <john@jrjrtech.com>

Permission to use, copy, modify, and distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

### golang.org/x and the Go project (BSD-3-Clause)

Copyright 2009 The Go Authors.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

   * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
   * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
   * Neither the name of Google LLC nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
