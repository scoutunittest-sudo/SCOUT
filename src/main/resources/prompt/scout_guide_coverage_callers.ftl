You are generating JUnit tests for the target Java method.

Scenario:
${scenario!""}

Checklist:
${checklist!""}

Target context:
${information!""}

Constructor dependencies:
<#if constructor_deps?? && constructor_deps?is_hash_ex>
<#list constructor_deps?keys as dep>
${dep}: ${constructor_deps[dep]!""}
</#list>
<#else>
${constructor_deps!""}
</#if>

Method dependencies:
<#if method_deps?? && method_deps?is_hash_ex>
<#list method_deps?keys as dep>
${dep}: ${method_deps[dep]!""}
</#list>
<#else>
${method_deps!""}
</#if>

Cached constructor/setup examples:
${cached_tests!""}

<#if (uncovered_scenarios!"")?trim?has_content>
Uncovered scenarios to repair:
${uncovered_scenarios!""}

</#if>
<#if (tagged_scenario_attempts!"")?trim?has_content>
Tagged scenario attempts from previous unit test:
${tagged_scenario_attempts!""}

</#if>
<#if (tagged_scenario_test_examples!"")?trim?has_content>
Previous tagged test methods for target scenarios:
${tagged_scenario_test_examples!""}

</#if>
Target scenario cards to satisfy:
${scenario_guidance!""}

Focus only on the target scenario cards above. Generate tests that satisfy each card's condition and expected behavior. Use relevant repair evidence only when it is shown. Avoid rewriting already covered scenarios unless needed for shared setup.
Use uncovered scenarios and uncovered regions as the primary coverage repair targets.
Keep previous working tests unless they block compilation. Prefer adding new test methods for uncovered scenarios instead of rewriting covered scenarios.

Uncovered regions to target:
${uncovered_regions!""}

Previous unit test:
${unit_test!""}

When a generated test targets a scenario card with an id, add the comment // SCOUT-SCENARIO: <scenario-id> immediately above the corresponding @Test method. Use the exact scenario id from the card; if no scenario id is available, omit the tag.

Return only compilable Java test code or test methods.
