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

package io.trino.plugin.teradata;

import io.trino.plugin.jdbc.JdbcClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connector is documented as read-only, and this is what makes that claim testable.
 * <p>
 * Refusing in toWriteMapping is not enough: DefaultJdbcMetadata reaches DELETE, UPDATE, TRUNCATE,
 * DROP and the schema and column mutations through JdbcClient directly, and BaseJdbcClient
 * implements every one of them by building remote SQL. This walks the JdbcClient interface instead
 * of a hand-written list, so a Trino upgrade that adds another mutating method fails here rather
 * than silently widening what the connector will do to a customer's database.
 */
final class TestTeradataClientReadOnly
{
    // Matched on the interface's own names. Anything that creates, changes or removes remote state.
    private static final List<String> MUTATING_PREFIXES = List.of(
            "create",
            "drop",
            "rename",
            "add",
            "set",
            "delete",
            "update",
            "truncate",
            "beginCreateTable",
            "beginInsertTable",
            "finishInsertTable",
            "commitCreateTable");

    // Read-side methods whose names begin with a mutating prefix by coincidence.
    private static final Set<String> NOT_MUTATIONS = Set.of("setNull");

    @Test
    void testEveryMutatingJdbcClientOperationIsOverridden()
    {
        List<String> unguarded = Arrays.stream(JdbcClient.class.getMethods())
                .filter(method -> MUTATING_PREFIXES.stream().anyMatch(prefix -> method.getName().startsWith(prefix)))
                .filter(method -> !NOT_MUTATIONS.contains(method.getName()))
                .filter(method -> !isOverriddenByTeradataClient(method))
                .map(Method::getName)
                .distinct()
                .sorted()
                .collect(toImmutableList());

        assertThat(unguarded)
                .describedAs("TeradataClient must refuse every mutating JdbcClient operation; these reach BaseJdbcClient and would issue remote SQL")
                .isEmpty();
    }

    private static boolean isOverriddenByTeradataClient(Method method)
    {
        try {
            TeradataClient.class.getDeclaredMethod(method.getName(), method.getParameterTypes());
            return true;
        }
        catch (NoSuchMethodException e) {
            return false;
        }
    }
}
