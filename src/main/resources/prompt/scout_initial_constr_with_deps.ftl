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

Uncovered regions to target:
${uncovered_regions!""}

Previous unit test:
${unit_test!""}

Previous error:
${error_message!""}

When a generated test targets a scenario card with an id, add the comment // SCOUT-SCENARIO: <scenario-id> immediately above the corresponding @Test method. Use the exact scenario id from the card; if no scenario id is available, omit the tag.

Return only compilable Java test code or test methods.
