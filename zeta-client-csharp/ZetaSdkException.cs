/*
 * #%L
 * ZETA-Client
 * %%
 * (C) EY Strategy & Transactions GmbH, 2025, licensed for gematik GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 * #L%
 */

namespace ZetaSdk;

/// <summary>
/// Thrown when a ZETA SDK operation fails, either at the native interop layer
/// or as a result of an error reported by the native SDK (see
/// <see cref="ZetaClient.GetLastError"/>).
/// </summary>
public sealed class ZetaSdkException : Exception
{
    /// <summary>Initializes a new instance with the given error message.</summary>
    public ZetaSdkException(string message) : base(message) { }

    /// <summary>Initializes a new instance with the given error message and inner exception.</summary>
    public ZetaSdkException(string message, Exception inner) : base(message, inner) { }
}
