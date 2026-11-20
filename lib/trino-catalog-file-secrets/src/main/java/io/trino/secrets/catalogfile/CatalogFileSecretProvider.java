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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Resolves {@code <catalog>/<property>} to the value of that property in {@code <catalog>.properties} of
 * one directory, so that a {@code CREATE CATALOG} can name the properties of a catalog file kept outside
 * the statement instead of carrying their values.
 * <p>
 * The key may be {@code <catalog>@<tag>/<property>}. The tag is not read. It makes the property text of
 * two definitions of one catalog differ, and with it the catalog version Trino derives from that text:
 * a catalog created again with the same text reuses the instance of the dropped one while that is not
 * pruned yet, and would keep its old values.
 * <p>
 * The file is read on every call, the way Trino reads a catalog file: as Java properties from its bytes,
 * with the value trimmed. Errors name the catalog and the property, never a value.
 */
public final class CatalogFileSecretProvider
        implements SecretProvider
{
    // The catalog becomes a file name: nothing that could leave the directory
    private static final Pattern KEY = Pattern.compile("([A-Za-z0-9_]+)(?:@[A-Za-z0-9_-]*)?/(.+)", Pattern.DOTALL);

    private final Path directory;

    public CatalogFileSecretProvider(Path directory)
    {
        this.directory = requireNonNull(directory, "directory is null");
    }

    @Override
    public String resolveSecretValue(String key)
    {
        Matcher matcher = KEY.matcher(requireNonNull(key, "key is null"));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a catalog file reference, expected <catalog>/<property>");
        }
        String catalog = matcher.group(1);
        String property = matcher.group(2);
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(directory.resolve(catalog + ".properties"))) {
            properties.load(in);
        }
        catch (NoSuchFileException e) {
            throw new IllegalStateException("Catalog '%s' has no catalog file".formatted(catalog));
        }
        catch (IOException | IllegalArgumentException e) {
            // Not the cause: its message may quote the line it failed on
            throw new IllegalStateException("The catalog file of catalog '%s' cannot be read".formatted(catalog));
        }
        String value = properties.getProperty(property);
        if (value == null) {
            throw new IllegalStateException("The catalog file of catalog '%s' has no property '%s'".formatted(catalog, property));
        }
        return value.trim();
    }
}
