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

public class InMemoryStorage : ICustomStorage
{
    private readonly Dictionary<string, string> _data = new();
    private readonly Lock _lock = new();

    public void Put(string key, string value)
    {
        lock (_lock) _data[key] = value;
    }

    public string? Get(string key)
    {
        lock (_lock) return _data.TryGetValue(key, out var v) ? v : null;
    }

    public void Remove(string key)
    {
        lock (_lock) _data.Remove(key);
    }

    public void Clear()
    {
        lock (_lock) _data.Clear();
    }
}
