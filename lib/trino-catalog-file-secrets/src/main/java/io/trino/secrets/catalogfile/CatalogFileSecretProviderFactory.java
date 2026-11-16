/*
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
 */
package io.trino.secrets.catalogfile;

import io.airlift.spi.secrets.SecretProvider;
import io.airlift.spi.secrets.SecretProviderFactory;

import java.nio.file.Path;
import java.util.Map;

public final class CatalogFileSecretProviderFactory
        implements SecretProviderFactory
{
    static final String DIRECTORY = "directory";

    @Override
    public String getName()
    {
        return "catalog-file";
    }

    @Override
    public SecretProvider createSecretProvider(Map<String, String> config)
    {
        String directory = config.get(DIRECTORY);
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("The catalog-file secrets provider needs the '" + DIRECTORY + "' property");
        }
        return new CatalogFileSecretProvider(Path.of(directory.trim()));
    }
}
