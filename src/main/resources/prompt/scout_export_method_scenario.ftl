List the minimum scenario set needed to cover this method's distinct control-flow outcomes.

Strict output rules:
- Each scenario must contain only condition -> expected behavior.
- Use exactly this shape on one bullet: - condition -> expected behavior.
- Merge equivalent values that have the same condition and expected behavior.
- Do not output separate metadata fields, labels, or sections for branch, input, output, state, dependencies, setup, mocks, lines, or internal hints.
- Keep internal SCOUT hints out of the scenario text; static and dynamic analysis will track them separately.
- Include only externally visible expected behavior, including thrown exceptions when they are the expected behavior.

Method summary:
${method_summary!""}

Return the scenarios inside [SCENARIO] and [/SCENARIO].
