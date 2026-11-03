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

namespace ZetaSdk.Config;

/// <summary>
/// Registration/token status of a <see cref="ZetaSdk.ZetaClient"/>, as returned
/// by <see cref="ZetaSdk.ZetaClient.GetStatus"/>.
/// </summary>
public enum ZetaSdkStatus
{
    /// <summary>The client has not been registered with the authorization server.</summary>
    NotRegistered            = 0,

    /// <summary>The client is registered but holds no valid access or refresh token.</summary>
    RegisteredNoValidTokens  = 1,

    /// <summary>The client holds a valid refresh token but no valid access token.</summary>
    HasRefreshToken          = 2,

    /// <summary>The client holds both a valid access token and a valid refresh token.</summary>
    HasAccessAndRefreshToken = 3,

    /// <summary>The status could not be determined.</summary>
    Unknown                  = -1
}
