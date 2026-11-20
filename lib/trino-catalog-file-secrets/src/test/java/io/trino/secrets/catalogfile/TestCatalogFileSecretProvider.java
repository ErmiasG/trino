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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestCatalogFileSecretProvider
{
    @TempDir
    Path directory;

    @Test
    void testResolvesAPropertyOfTheNamedCatalog()
            throws IOException
    {
        write("sales__pg", "connector.name=postgresql\nconnection-url=jdbc\\:postgresql\\://db\\:5432/x\nconnection-password = s3cret  \n");
        write("other__pg", "connection-password=another\n");
        SecretProvider provider = provider();

        assertThat(provider.resolveSecretValue("sales__pg/connection-password")).isEqualTo("s3cret");
        assertThat(provider.resolveSecretValue("sales__pg/connection-url")).isEqualTo("jdbc:postgresql://db:5432/x");
        assertThat(provider.resolveSecretValue("other__pg/connection-password")).isEqualTo("another");
    }

    @Test
    void testTheTagIsNotRead()
            throws IOException
    {
        write("sales__pg", "connection-password=s3cret\n");

        assertThat(provider().resolveSecretValue("sales__pg@0a1b2c/connection-password")).isEqualTo("s3cret");
        assertThat(provider().resolveSecretValue("sales__pg@/connection-password")).isEqualTo("s3cret");
    }

    @Test
    void testAPropertyNameMayHoldSlashesAndDots()
            throws IOException
    {
        write("c", "a.b/c@d=v\n");

        assertThat(provider().resolveSecretValue("c/a.b/c@d")).isEqualTo("v");
    }

    @Test
    void testTheFileIsReadOnEveryCall()
            throws IOException
    {
        SecretProvider provider = provider();
        write("c", "p=one\n");
        assertThat(provider.resolveSecretValue("c/p")).isEqualTo("one");
        write("c", "p=two\n");
        assertThat(provider.resolveSecretValue("c/p")).isEqualTo("two");
    }

    @Test
    void testACatalogNameCannotLeaveTheDirectory()
            throws IOException
    {
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("c.properties"), "p=v\n", UTF_8);
        SecretProvider provider = new CatalogFileSecretProvider(Files.createDirectory(directory.resolve("catalogs")));

        for (String key : new String[] {"../outside/c/p", "..%2Foutside%2Fc/p", "/etc/passwd/p", "outside/c/p", "c.properties/p", "./c/p", "/p", "c", "c/", ""}) {
            assertThatThrownBy(() -> provider.resolveSecretValue(key))
                    .as(key)
                    .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
        }
    }

    @Test
    void testErrorsNameTheCatalogAndThePropertyAndNoValue()
            throws IOException
    {
        write("c", "p=s3cret\nbroken=\\u12\n");
        write("d", "p=s3cret\n");

        assertThatThrownBy(() -> provider().resolveSecretValue("missing/p"))
                .hasMessage("Catalog 'missing' has no catalog file");
        assertThatThrownBy(() -> provider().resolveSecretValue("d/q"))
                .hasMessage("The catalog file of catalog 'd' has no property 'q'");
        assertThatThrownBy(() -> provider().resolveSecretValue("c/p"))
                .hasMessage("The catalog file of catalog 'c' cannot be read")
                .hasNoCause();
    }

    @Test
    void testTheFactoryNeedsADirectory()
    {
        CatalogFileSecretProviderFactory factory = new CatalogFileSecretProviderFactory();
        assertThat(factory.getName()).isEqualTo("catalog-file");
        assertThatThrownBy(() -> factory.createSecretProvider(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(factory.createSecretProvider(Map.of("directory", directory.toString()))).isNotNull();
    }

    private SecretProvider provider()
    {
        return new CatalogFileSecretProvider(directory);
    }

    private void write(String catalog, String content)
            throws IOException
    {
        Files.writeString(directory.resolve(catalog + ".properties"), content, UTF_8);
    }
}
