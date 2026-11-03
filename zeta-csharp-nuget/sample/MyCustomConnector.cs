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

using ZetaSdk.Config;

public class MyCustomConnector : ICustomSmcbConnector
{
    public async Task<byte[]> ReadCertificateAsync()
    {
        byte[] dummyCert = [0x01, 0x02, 0x03, 0x04];

        Console.WriteLine("call ReadCertificateAsync");
        return await Task.FromResult(dummyCert);
    }

    public async Task<byte[]> ExternalAuthenticateAsync(string challenge)
    {
        byte[] dummySignature = [0xAA, 0xBB, 0xCC, 0xDD];
        Console.WriteLine("call ExternalAuthenticateAsync");

        return await Task.FromResult(dummySignature);
    }
}
