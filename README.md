<img align="right" width="250" height="47" src="docs/img/Gematik_Logo_Flag.png"/> <br/>

# zeta-sdk

## Architecture overview

The Zeta Client SDK is build with Kotlin Multiplatform (KMP) to target multiple platforms (iOS, Android and JVM),
while reducing the duplicated code.
By sharing core logic across platforms, minimizing the maintenance overhead and ensure consistent behaviour for all
clients.
The SDK is organized into the following modules following a layered architecture.

Each module has a clear responsibility and depends on the layer beneath it:

- Common: provides cross-platform utilities. Other modules rely on Common for core functionality.
- Network: responsible for the communication with the APIs (ZETA-Guard). Uses **Ktor** as the HTTP client to perform API request in a multiplatform way. Serialization is handled by **Kotlinx Serialization**, ensuring type-safe JSON parsing across all targets.
- SDK: exposes a unified API to the clients.
- Client: Android, iOS, JVM (Linux, Windows, macOS) applications. This depends on SDK, to not having to access the low level modules.

## License

(C) EY Strategy and Transactions GmbH, 2025, licensed for gematik GmbH

Apache License, Version 2.0

See the [LICENSE](./LICENSE) for the specific language governing permissions and limitations under the License

## Additional Notes and Disclaimer from gematik GmbH

1. Copyright notice: Each published work result is accompanied by an explicit statement of the license conditions for use. These are regularly typical conditions in connection with open source or free software. Programs described/provided/linked here are free software, unless otherwise stated.
2. Permission notice: Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
   1. The copyright notice (Item 1) and the permission notice (Item 2) shall be included in all copies or substantial portions of the Software.
   2. The software is provided "as is" without warranty of any kind, either express or implied, including, but not limited to, the warranties of fitness for a particular purpose, merchantability, and/or non-infringement. The authors or copyright holders shall not be liable in any manner whatsoever for any damages or other claims arising from, out of or in connection with the software or the use or other dealings with the software, whether in an action of contract, tort, or otherwise.
   3. We take open source license compliance very seriously. We are always striving to achieve compliance at all times and to improve our processes. If you find any issues or have any suggestions or comments, or if you see any other ways in which we can improve, please reach out to: ospo@gematik.de
3. Please note: Parts of this code may have been generated using AI-supported technology. Please take this into account, especially when troubleshooting, for security analyses and possible adjustments.
