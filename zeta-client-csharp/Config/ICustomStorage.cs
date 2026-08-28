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

/// <summary>
/// Implement to back the SDK's persistence (tokens, keys, session data) with a
/// custom store instead of the SDK's default platform storage. Configured via
/// <see cref="ZetaSdk.Config.ZetaStorageConfig.CustomStorage"/>.
/// </summary>
public interface ICustomStorage
{
    /// <summary>Stores <paramref name="value"/> under <paramref name="key"/>, overwriting any existing value.</summary>
    void Put(string key, string value);

    /// <summary>Returns the value stored under <paramref name="key"/>, or <c>null</c> if not present.</summary>
    string? Get(string key);

    /// <summary>Removes the value stored under <paramref name="key"/>, if any.</summary>
    void Remove(string key);

    /// <summary>Removes all values from this storage.</summary>
    void Clear();
}
