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
package io.trino.plugin.base.security;

import com.google.common.collect.ImmutableSet;
import io.trino.spi.TrinoException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestReplacePatternMatcher
{
    private static final Set<String> SHARED_GROUPS = ImmutableSet.of("a__shared", "b__shared", "c__shared");

    @Test
    public void testEveryMatchingGroupIsTried()
    {
        ReplacePatternMatcher matcher = groupMatcher("(.*)__shared", SHARED_GROUPS);

        assertThat(matcher.matchCatalog(resource("$1_catalog"), "a_catalog")).isTrue();
        assertThat(matcher.matchCatalog(resource("$1_catalog"), "c_catalog")).isTrue();
        assertThat(matcher.matchCatalog(resource("$1_catalog"), "d_catalog")).isFalse();

        assertThat(matcher.matchSchema(resource("$1_featurestore"), "a_featurestore")).isTrue();
        assertThat(matcher.matchSchema(resource("$1_featurestore"), "b_featurestore")).isTrue();
        assertThat(matcher.matchSchema(resource("$1_featurestore"), "c_featurestore")).isTrue();
        assertThat(matcher.matchSchema(resource("$1_featurestore"), "d_featurestore")).isFalse();

        assertThat(matcher.matchSchemaAndTable(resource(".*"), "any", resource("$1_table"), "b_table")).isTrue();
        assertThat(matcher.matchSchemaAndTable(resource(".*"), "any", resource("$1_table"), "d_table")).isFalse();

        assertThat(matcher.matchQueryAccessRule(pattern("$1__.*"), Optional.of("b__ann"))).isTrue();
        assertThat(matcher.matchQueryAccessRule(pattern("$1__.*"), Optional.of("d__ann"))).isFalse();
    }

    @Test
    public void testEveryMatchingRoleIsTried()
    {
        ReplacePatternMatcher matcher = new ReplacePatternMatcher(Optional.empty(), pattern("(.*)_reader"), Optional.empty(), "ann", ImmutableSet.of("a_reader", "b_reader"), ImmutableSet.of());

        assertThat(matcher.matchSchema(resource("$1"), "a")).isTrue();
        assertThat(matcher.matchSchema(resource("$1"), "b")).isTrue();
        assertThat(matcher.matchSchema(resource("$1"), "c")).isFalse();
    }

    @Test
    public void testOneGroupBindsEveryField()
    {
        ReplacePatternMatcher matcher = groupMatcher("(.*)__shared", SHARED_GROUPS);

        assertThat(matcher.matchCatalogAndSchema(resource("$1_catalog"), "a_catalog", resource("$1_schema"), "a_schema")).isTrue();
        assertThat(matcher.matchCatalogAndSchema(resource("$1_catalog"), "a_catalog", resource("$1_schema"), "b_schema")).isFalse();

        assertThat(matcher.matchSchemaAndTable(resource("$1_schema"), "b_schema", resource("$1_table"), "b_table")).isTrue();
        assertThat(matcher.matchSchemaAndTable(resource("$1_schema"), "b_schema", resource("$1_table"), "c_table")).isFalse();

        assertThat(matcher.matchCatalogSchemaAndTable(resource("$1_catalog"), "c_catalog", resource("$1_schema"), "c_schema", resource("$1_table"), "c_table")).isTrue();
        assertThat(matcher.matchCatalogSchemaAndTable(resource("$1_catalog"), "c_catalog", resource("$1_schema"), "c_schema", resource("$1_table"), "a_table")).isFalse();
    }

    @Test
    public void testSeveralCapturingGroups()
    {
        ReplacePatternMatcher matcher = groupMatcher("(.*)__(.*)", ImmutableSet.of("a__reader", "b__writer"));

        assertThat(matcher.matchSchema(resource("$1_$2"), "a_reader")).isTrue();
        assertThat(matcher.matchSchema(resource("$1_$2"), "b_writer")).isTrue();
        assertThat(matcher.matchSchema(resource("$1_$2"), "a_writer")).isFalse();
    }

    @Test
    public void testPatternsWithoutReferences()
    {
        ReplacePatternMatcher matcher = groupMatcher("(.*)__shared", SHARED_GROUPS);

        assertThat(matcher.matchSchema(resource("public"), "public")).isTrue();
        assertThat(matcher.matchSchema(Optional.empty(), "anything")).isTrue();
        assertThat(groupMatcher("staff", ImmutableSet.of("staff")).matchSchema(resource("$1_featurestore"), "a_featurestore")).isFalse();
    }

    @Test
    public void testPrincipalConditions()
    {
        ReplacePatternMatcher noConditions = new ReplacePatternMatcher(Optional.empty(), Optional.empty(), Optional.empty(), "ann", ImmutableSet.of(), ImmutableSet.of());
        assertThat(noConditions.matchSchema(resource(".*"), "public")).isTrue();

        ReplacePatternMatcher noGroups = groupMatcher("(.*)__shared", ImmutableSet.of());
        assertThat(noGroups.matchSchema(resource(".*"), "public")).isFalse();

        ReplacePatternMatcher otherUser = new ReplacePatternMatcher(pattern("bob"), Optional.empty(), Optional.empty(), "ann", ImmutableSet.of(), ImmutableSet.of());
        assertThat(otherUser.matchSchema(resource(".*"), "public")).isFalse();
    }

    @Test
    public void testQueryRuleWithoutOwnerChecksPrincipal()
    {
        assertThat(groupMatcher("admin", ImmutableSet.of("admin")).matchQueryAccessRule(Optional.empty(), Optional.empty())).isTrue();
        assertThat(groupMatcher("admin", ImmutableSet.of("q__data_owner")).matchQueryAccessRule(Optional.empty(), Optional.empty())).isFalse();
        assertThat(groupMatcher("admin", ImmutableSet.of("admin")).matchQueryAccessRule(pattern(".*"), Optional.empty())).isFalse();
        assertThat(groupMatcher("admin", ImmutableSet.of("admin")).matchQueryAccessRule(Optional.empty(), Optional.of("bob"))).isTrue();
    }

    @Test
    public void testCaptureFromUser()
    {
        ReplacePatternMatcher matcher = new ReplacePatternMatcher(pattern("(.*)__.*"), Optional.empty(), Optional.empty(), "q__ann", ImmutableSet.of(), ImmutableSet.of());

        assertThat(matcher.matchQueryAccessRule(pattern("$1__.*"), Optional.of("q__bob"))).isTrue();
        assertThat(matcher.matchQueryAccessRule(pattern("$1__.*"), Optional.of("p__bob"))).isFalse();
        assertThat(matcher.matchSchema(resource("$1_featurestore"), "q_featurestore")).isTrue();
    }

    @Test
    public void testCapturedValuesAreLiterals()
    {
        ReplacePatternMatcher matcher = groupMatcher("(.*)__shared", ImmutableSet.of(".*__shared", "a|b__shared"));

        assertThat(matcher.matchSchema(resource("$1_featurestore"), ".*_featurestore")).isTrue();
        assertThat(matcher.matchSchema(resource("$1_featurestore"), "victim_featurestore")).isFalse();
        assertThat(matcher.matchSchema(resource("$1_featurestore"), "a|b_featurestore")).isTrue();
        assertThat(matcher.matchSchema(resource("$1_featurestore"), "b_featurestore")).isFalse();
    }

    @Test
    public void testResourcePatternIsKeptAsWritten()
    {
        ReplacePatternMatcher matcher = groupMatcher("(.*)__shared", ImmutableSet.of("a__shared"));

        assertThat(matcher.matchSchema(resource("$1\\.fs"), "a.fs")).isTrue();
        assertThat(matcher.matchSchema(resource("$1\\.fs"), "aXfs")).isFalse();
        assertThat(matcher.matchSchema(resource("$1_\\d+"), "a_12")).isTrue();
        assertThat(matcher.matchSchema(resource("\\$1|$1_s"), "$1")).isTrue();
        assertThat(matcher.matchSchema(resource("$1_fs$"), "a_fs")).isTrue();
        assertThat(matcher.matchSchema(resource("$10"), "a0")).isTrue();
    }

    @Test
    public void testReferenceInsideQuotedSection()
    {
        ReplacePatternMatcher matcher = groupMatcher("(.*)__shared", ImmutableSet.of("a__shared", "x\\Ey__shared"));

        assertThat(matcher.matchSchema(resource("\\Q$1\\E"), "a")).isTrue();
        assertThat(matcher.matchSchema(resource("\\Q$1.*"), "a.*")).isTrue();
        assertThat(matcher.matchSchema(resource("\\Q$1.*"), "abc")).isFalse();
        assertThat(matcher.matchSchema(resource("\\Q$1_$1\\E"), "a_a")).isTrue();
        assertThat(matcher.matchSchema(resource("$1_fs"), "x\\Ey_fs")).isTrue();
        assertThat(matcher.matchSchema(resource("\\Q$1\\E_fs"), "x\\Ey_fs")).isTrue();
    }

    @Test
    public void testReferenceNumbering()
    {
        ReplacePatternMatcher whole = groupMatcher("(.*)__shared(_ro)?", ImmutableSet.of("a__shared"));
        assertThat(whole.matchSchema(resource("$0_x"), "a__shared_x")).isTrue();
        assertThat(whole.matchSchema(resource("$1$2_fs"), "a_fs")).isTrue();
        assertThat(whole.matchSchema(resource("$01"), "a")).isTrue();

        ReplacePatternMatcher tenGroups = groupMatcher("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)__shared", ImmutableSet.of("abcdefghij__shared"));
        assertThat(tenGroups.matchSchema(resource("$10$1"), "ja")).isTrue();
        assertThat(tenGroups.matchSchema(resource("$11"), "a1")).isTrue();
    }

    @Test
    public void testSubstitutedPatternThatDoesNotCompile()
    {
        ReplacePatternMatcher matcher = groupMatcher("(.*)__shared", ImmutableSet.of("z__shared"));

        assertThatThrownBy(() -> matcher.matchSchema(resource("[$1-a]"), "b"))
                .isInstanceOfSatisfying(TrinoException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CONFIGURATION_INVALID.toErrorCode()))
                .hasMessageStartingWith("schema in replace pattern is not a valid pattern once group values are substituted");
    }

    @Test
    public void testReferenceToMissingCapturingGroup()
    {
        ReplacePatternMatcher matcher = groupMatcher("(.*)__shared", SHARED_GROUPS);

        assertThatThrownBy(() -> matcher.matchSchema(resource("$2_featurestore"), "a_featurestore"))
                .isInstanceOfSatisfying(TrinoException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CONFIGURATION_INVALID.toErrorCode()))
                .hasMessage("schema in replace pattern refers to a capturing group that does not exist in group");
    }

    @Test
    public void testCapturingGroupsFromSeveralPrincipalFields()
    {
        ReplacePatternMatcher matcher = new ReplacePatternMatcher(pattern("(.*)__ann"), Optional.empty(), pattern("(.*)__shared"), "q__ann", ImmutableSet.of(), SHARED_GROUPS);

        assertThatThrownBy(() -> matcher.matchSchema(resource("$1"), "q"))
                .isInstanceOfSatisfying(TrinoException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CONFIGURATION_INVALID.toErrorCode()))
                .hasMessageContaining("Multiple matchers that contain capturing groups");
    }

    @Test
    public void testUserPlaceholderNextToCapturingGroup()
    {
        ReplacePatternMatcher matcher = groupMatcher("(.*)__shared", SHARED_GROUPS);

        assertThat(matcher.matchSchema(resource("$1_{user}"), "a_q__ann")).isTrue();
        assertThat(matcher.matchSchema(resource("$1_{user}"), "d_q__ann")).isFalse();
        assertThat(matcher.matchSchema(resource("$1_{user}"), "a_q__bob")).isFalse();
        // without a capturing group reference the placeholder is upstream's
        assertThat(matcher.matchSchema(resource("{user}_home"), "q__ann_home")).isTrue();
        assertThat(matcher.matchSchema(resource("{user}_home"), "q__bob_home")).isFalse();
    }

    @Test
    public void testSubstitutedValuesAreNotReadAsPlaceholders()
    {
        // a user named after a capturing group reference, and a group whose capture spells the placeholder
        ReplacePatternMatcher userNamedReference = new ReplacePatternMatcher(Optional.empty(), Optional.empty(), pattern("(.*)__shared"), "$1", ImmutableSet.of(), ImmutableSet.of("a__shared"));
        assertThat(userNamedReference.matchSchema(resource("$1_{user}"), "a_$1")).isTrue();
        assertThat(userNamedReference.matchSchema(resource("$1_{user}"), "a_a")).isFalse();

        ReplacePatternMatcher capturedPlaceholder = new ReplacePatternMatcher(Optional.empty(), Optional.empty(), pattern("(.*)__shared"), "ann", ImmutableSet.of(), ImmutableSet.of("{user}__shared"));
        assertThat(capturedPlaceholder.matchSchema(resource("$1_home"), "{user}_home")).isTrue();
        assertThat(capturedPlaceholder.matchSchema(resource("$1_home"), "ann_home")).isFalse();
    }

    private static ReplacePatternMatcher groupMatcher(String groupRegex, Set<String> groups)
    {
        return new ReplacePatternMatcher(Optional.empty(), Optional.empty(), pattern(groupRegex), "q__ann", ImmutableSet.of(), groups);
    }

    private static Optional<Pattern> pattern(String regex)
    {
        return Optional.of(Pattern.compile(regex));
    }

    private static Optional<UserSubstitutingPattern> resource(String regex)
    {
        return Optional.of(UserSubstitutingPattern.of(regex));
    }
}
