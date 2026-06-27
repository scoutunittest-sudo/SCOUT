package zju.cst.aces.scout.scenario;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScoutScenarioAnalyzer {
    private static final Pattern SCENARIO_MARKER =
            Pattern.compile("^\\s*(?:\\d+[\\.)]|[-*])\\s+(.+?)\\s*$");
    private static final Set<String> SHORT_STOPWORDS = shortStopwords();
    private final JavaParser javaParser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));

    public List<ScoutScenario> analyze(String scenarioText, String sourceCode) {
        return analyze(scenarioText, sourceCode, 1);
    }

    public List<ScoutScenario> analyze(String scenarioText, String sourceCode, int sourceStartLine) {
        List<ScoutScenario> scenarios = parseScenarios(scenarioText);
        if (scenarios.isEmpty()) {
            return scenarios;
        }
        try {
            enrichWithStaticHints(scenarios, sourceCode, sourceStartLine);
        } catch (RuntimeException ignored) {
            return scenarios;
        }
        return scenarios;
    }

    private List<ScoutScenario> parseScenarios(String scenarioText) {
        List<ScoutScenario> scenarios = new ArrayList<ScoutScenario>();
        String text = scenarioText == null ? "" : scenarioText.trim();
        if (text.isEmpty()) {
            return scenarios;
        }

        String[] lines = text.split("\\r\\n|\\n|\\r");
        List<String> descriptions = new ArrayList<String>();
        for (String line : lines) {
            Matcher matcher = SCENARIO_MARKER.matcher(line);
            if (matcher.matches()) {
                descriptions.add(matcher.group(1).trim());
            }
        }
        if (descriptions.isEmpty()) {
            descriptions.add(text.replaceAll("\\s+", " "));
        }

        for (int i = 0; i < descriptions.size(); i++) {
            ScoutScenario scenario = new ScoutScenario();
            scenario.setId("scenario-" + (i + 1));
            scenario.setDescription(descriptions.get(i));
            scenarios.add(scenario);
        }
        return scenarios;
    }

    private void enrichWithStaticHints(List<ScoutScenario> scenarios, String sourceCode, int sourceStartLine) {
        String source = sourceCode == null ? "" : sourceCode;
        ParsedSource parsedSource = parseSource(source, sourceStartLine);
        CompilationUnit compilationUnit = parsedSource.compilationUnit;

        Set<String> parameterNames = collectParameterNames(compilationUnit);
        Set<String> fieldNames = collectFieldNames(compilationUnit);
        Set<String> localNames = collectLocalNames(compilationUnit);
        Map<String, Influence> influenceIndex = collectLocalInfluences(compilationUnit, parameterNames, fieldNames, localNames);
        List<BranchHint> branchHints = collectBranchHints(
                compilationUnit,
                parsedSource.lineAdjustment,
                parameterNames,
                fieldNames,
                localNames,
                influenceIndex);
        List<CallHint> callHints = collectCallHints(compilationUnit, parsedSource.lineAdjustment);

        for (ScoutScenario scenario : scenarios) {
            enrichScenario(scenario, branchHints, callHints, parameterNames);
        }
    }

    private ParsedSource parseSource(String source, int sourceStartLine) {
        try {
            return new ParsedSource(parseCompilationUnit(source), 0);
        } catch (RuntimeException ignored) {
            int startLine = sourceStartLine <= 0 ? 1 : sourceStartLine;
            return new ParsedSource(
                    parseCompilationUnit("class ScoutScenarioSourceWrapper {\n" + source + "\n}"),
                    startLine - 2);
        }
    }

    private CompilationUnit parseCompilationUnit(String source) {
        ParseResult<CompilationUnit> result = javaParser.parse(source);
        if (!result.isSuccessful() || !result.getResult().isPresent()) {
            throw new IllegalArgumentException("Source could not be parsed");
        }
        return result.getResult().get();
    }

    private List<BranchHint> collectBranchHints(CompilationUnit compilationUnit,
                                                int lineAdjustment,
                                                Set<String> parameterNames,
                                                Set<String> fieldNames,
                                                Set<String> localNames,
                                                Map<String, Influence> influenceIndex) {
        List<BranchHint> branchHints = new ArrayList<BranchHint>();
        List<Node> nodes = compilationUnit.findAll(Node.class);
        for (Node node : nodes) {
            if (node instanceof BinaryExpr && isShortCircuitBinary((BinaryExpr) node)) {
                BinaryExpr binaryExpr = (BinaryExpr) node;
                int line = beginLine(node, lineAdjustment);
                branchHints.add(branchHint(binaryExpr.getLeft(), node.toString(), line, -1,
                        parameterNames, fieldNames, localNames, influenceIndex));
                branchHints.add(branchHint(binaryExpr.getRight(), node.toString(), line, -1,
                        parameterNames, fieldNames, localNames, influenceIndex));
            } else if (isBranchNode(node)) {
                branchHints.add(branchHint(
                        branchExpression(node),
                        node.toString(),
                        beginLine(node, lineAdjustment),
                        trueBranchFlagLine(node, lineAdjustment),
                        parameterNames,
                        fieldNames,
                        localNames,
                        influenceIndex));
            }
        }
        return branchHints;
    }

    private BranchHint branchHint(Expression expression,
                                  String fallbackSearchText,
                                  int line,
                                  int trueBranchFlagLine,
                                  Set<String> parameterNames,
                                  Set<String> fieldNames,
                                  Set<String> localNames,
                                  Map<String, Influence> influenceIndex) {
        String hintText = expression == null ? safe(fallbackSearchText) : expression.toString();
        Influence influence = expression == null
                ? new Influence()
                : influence(expression, parameterNames, fieldNames, localNames, influenceIndex);
        String searchText = hintText + " " + fallbackSearchText + " " + influence.searchText();
        return new BranchHint(hintText, searchText, line, trueBranchFlagLine, influence);
    }

    private List<CallHint> collectCallHints(CompilationUnit compilationUnit, int lineAdjustment) {
        List<CallHint> callHints = new ArrayList<CallHint>();
        List<MethodCallExpr> methodCalls = compilationUnit.findAll(MethodCallExpr.class);
        for (MethodCallExpr methodCall : methodCalls) {
            String dependency = methodCall.getScope().isPresent()
                    ? methodCall.getScope().get().toString() + "." + methodCall.getNameAsString()
                    : methodCall.getNameAsString();
            callHints.add(new CallHint(dependency, methodCall.toString(), beginLine(methodCall, lineAdjustment)));
        }

        List<ObjectCreationExpr> objectCreations = compilationUnit.findAll(ObjectCreationExpr.class);
        for (ObjectCreationExpr objectCreation : objectCreations) {
            callHints.add(new CallHint(objectCreation.getType().getNameAsString(), objectCreation.toString(), beginLine(objectCreation, lineAdjustment)));
        }
        return callHints;
    }

    private Set<String> collectParameterNames(CompilationUnit compilationUnit) {
        Set<String> parameterNames = new LinkedHashSet<String>();
        List<MethodDeclaration> methods = compilationUnit.findAll(MethodDeclaration.class);
        for (MethodDeclaration method : methods) {
            for (Parameter parameter : method.getParameters()) {
                parameterNames.add(parameter.getNameAsString());
            }
        }
        return parameterNames;
    }

    private Set<String> collectFieldNames(CompilationUnit compilationUnit) {
        Set<String> fieldNames = new LinkedHashSet<String>();
        List<FieldDeclaration> fields = compilationUnit.findAll(FieldDeclaration.class);
        for (FieldDeclaration field : fields) {
            for (VariableDeclarator variable : field.getVariables()) {
                fieldNames.add(variable.getNameAsString());
            }
        }
        return fieldNames;
    }

    private Set<String> collectLocalNames(CompilationUnit compilationUnit) {
        Set<String> localNames = new LinkedHashSet<String>();
        List<VariableDeclarator> variables = compilationUnit.findAll(VariableDeclarator.class);
        for (VariableDeclarator variable : variables) {
            if (!variable.findAncestor(FieldDeclaration.class).isPresent()) {
                localNames.add(variable.getNameAsString());
            }
        }
        return localNames;
    }

    private Map<String, Influence> collectLocalInfluences(CompilationUnit compilationUnit,
                                                          Set<String> parameterNames,
                                                          Set<String> fieldNames,
                                                          Set<String> localNames) {
        Map<String, Influence> index = new HashMap<String, Influence>();
        List<VariableDeclarator> variables = compilationUnit.findAll(VariableDeclarator.class);
        for (VariableDeclarator variable : variables) {
            if (variable.findAncestor(FieldDeclaration.class).isPresent() || !variable.getInitializer().isPresent()) {
                continue;
            }
            index.put(variable.getNameAsString(),
                    influence(variable.getInitializer().get(), parameterNames, fieldNames, localNames, index));
        }

        List<AssignExpr> assignments = compilationUnit.findAll(AssignExpr.class);
        for (AssignExpr assignment : assignments) {
            String target = assignment.getTarget().toString();
            if (target.startsWith("this.")) {
                target = target.substring("this.".length());
            }
            if (target.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                index.put(target, influence(assignment.getValue(), parameterNames, fieldNames, localNames, index));
            }
        }
        return index;
    }

    private void enrichScenario(ScoutScenario scenario, List<BranchHint> branchHints, List<CallHint> callHints, Set<String> parameterNames) {
        Set<String> branches = new LinkedHashSet<String>();
        Set<String> arguments = new LinkedHashSet<String>();
        Set<String> dependencies = new LinkedHashSet<String>();
        Set<Integer> lines = new LinkedHashSet<Integer>();
        List<ScoutBranchMapping> branchMappings = new ArrayList<ScoutBranchMapping>();

        Set<String> scenarioTokens = tokens(scenario.getDescription());

        for (BranchHint branchHint : branchHints) {
            if (matches(scenarioTokens, branchHint.searchText)) {
                addNonEmpty(branches, branchHint.hintText);
                addLine(lines, branchHint.line);
                addArgumentHints(arguments, parameterNames, scenarioTokens, branchHint.searchText);
                addBranchControlHints(arguments, dependencies, branchHint);
                branchMappings.add(branchMapping(branchHint, scenarioTokens));
            }
        }

        for (CallHint callHint : callHints) {
            if (matches(scenarioTokens, callHint.searchText)) {
                addNonEmpty(dependencies, callHint.dependencyText);
                addLine(lines, callHint.line);
                addArgumentHints(arguments, parameterNames, scenarioTokens, callHint.searchText);
            }
        }

        scenario.setBranchHints(new ArrayList<String>(branches));
        scenario.setArgumentHints(new ArrayList<String>(arguments));
        scenario.setDependencyHints(new ArrayList<String>(dependencies));
        scenario.setRelatedLines(new ArrayList<Integer>(lines));
        scenario.setBranchMappings(new ArrayList<ScoutBranchMapping>(branchMappings));
    }

    private ScoutBranchMapping branchMapping(BranchHint branchHint, Set<String> scenarioTokens) {
        ScoutBranchMapping mapping = new ScoutBranchMapping();
        mapping.setBranchText(branchHint.hintText);
        mapping.setConditionLine(branchHint.line);
        mapping.setTrueBranchFlagLine(branchHint.trueBranchFlagLine);
        mapping.setOutcome(inferOutcome(scenarioTokens, branchHint.hintText));
        mapping.setParameterDependencies(new ArrayList<String>(branchHint.influence.parameters));
        mapping.setFieldDependencies(new ArrayList<String>(branchHint.influence.fields));
        mapping.setCallDependencies(new ArrayList<String>(branchHint.influence.calls));
        return mapping;
    }

    private void addBranchControlHints(Set<String> arguments, Set<String> dependencies, BranchHint branchHint) {
        for (String parameter : branchHint.influence.parameters) {
            addNonEmpty(arguments, parameter + " controls branch: " + branchHint.hintText);
        }
        for (String field : branchHint.influence.fields) {
            addNonEmpty(dependencies, "field " + field + " controls branch: " + branchHint.hintText);
        }
        for (String call : branchHint.influence.calls) {
            addNonEmpty(dependencies, "call " + call + " controls branch: " + branchHint.hintText);
        }
    }

    private Influence influence(Expression expression,
                                Set<String> parameterNames,
                                Set<String> fieldNames,
                                Set<String> localNames,
                                Map<String, Influence> influenceIndex) {
        Influence influence = new Influence();
        if (expression == null) {
            return influence;
        }

        for (NameExpr nameExpr : expression.findAll(NameExpr.class)) {
            String name = nameExpr.getNameAsString();
            if (parameterNames.contains(name)) {
                influence.parameters.add(name);
            } else if (influenceIndex.containsKey(name)) {
                influence.merge(influenceIndex.get(name));
            } else if (fieldNames.contains(name) || !localNames.contains(name)) {
                influence.fields.add(name);
            }
        }

        for (FieldAccessExpr fieldAccess : expression.findAll(FieldAccessExpr.class)) {
            String fieldName = fieldAccess.getNameAsString();
            if (fieldNames.isEmpty() || fieldNames.contains(fieldName) || fieldAccess.getScope().isThisExpr()) {
                influence.fields.add(fieldName);
            }
        }

        for (MethodCallExpr methodCall : expression.findAll(MethodCallExpr.class)) {
            String call = methodCall.getScope().isPresent()
                    ? methodCall.getScope().get().toString() + "." + methodCall.getNameAsString()
                    : methodCall.getNameAsString();
            influence.calls.add(call);
            if (methodCall.getScope().isPresent()) {
                String scope = methodCall.getScope().get().toString();
                if (fieldNames.contains(scope) || (!parameterNames.contains(scope) && !localNames.contains(scope))) {
                    influence.fields.add(scope);
                }
            }
        }
        return influence;
    }

    private boolean matches(Set<String> scenarioTokens, String codeText) {
        Set<String> codeTokens = tokens(codeText);
        return hasMeaningfulTokenOverlap(scenarioTokens, codeTokens);
    }

    private boolean hasMeaningfulTokenOverlap(Set<String> scenarioTokens, Set<String> codeTokens) {
        for (String token : scenarioTokens) {
            if (isMeaningfulToken(token) && codeTokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMeaningfulToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        return token.length() > 2 || !SHORT_STOPWORDS.contains(token);
    }

    private void addArgumentHints(Set<String> arguments, Set<String> parameterNames, Set<String> scenarioTokens, String codeText) {
        Set<String> codeTokens = tokens(codeText);
        for (String parameterName : parameterNames) {
            String loweredParameter = lower(parameterName);
            if (scenarioTokens.contains(loweredParameter) && codeTokens.contains(loweredParameter)) {
                addNonEmpty(arguments, parameterName);
            }
        }
    }

    private boolean isBranchNode(Node node) {
        return node instanceof IfStmt
                || node instanceof DoStmt
                || node instanceof SwitchStmt
                || node instanceof SwitchExpr
                || node instanceof WhileStmt
                || node instanceof ForStmt
                || node instanceof ForEachStmt
                || node instanceof ConditionalExpr;
    }

    private boolean isShortCircuitBinary(BinaryExpr binaryExpr) {
        return binaryExpr.getOperator() == BinaryExpr.Operator.AND
                || binaryExpr.getOperator() == BinaryExpr.Operator.OR;
    }

    private int trueBranchFlagLine(Node node, int lineAdjustment) {
        if (node instanceof IfStmt) {
            return firstLine(((IfStmt) node).getThenStmt(), lineAdjustment);
        }
        if (node instanceof WhileStmt) {
            return firstLine(((WhileStmt) node).getBody(), lineAdjustment);
        }
        if (node instanceof ForStmt) {
            return firstLine(((ForStmt) node).getBody(), lineAdjustment);
        }
        if (node instanceof ForEachStmt) {
            return firstLine(((ForEachStmt) node).getBody(), lineAdjustment);
        }
        if (node instanceof DoStmt) {
            return firstLine(((DoStmt) node).getBody(), lineAdjustment);
        }
        if (node instanceof ConditionalExpr) {
            return beginLine(((ConditionalExpr) node).getThenExpr(), lineAdjustment);
        }
        return -1;
    }

    private int firstLine(Statement statement, int lineAdjustment) {
        if (statement == null) {
            return -1;
        }
        if (statement.isBlockStmt()) {
            List<Statement> statements = statement.asBlockStmt().getStatements();
            if (statements.isEmpty()) {
                return beginLine(statement, lineAdjustment);
            }
            return firstLine(statements.get(0), lineAdjustment);
        }
        return beginLine(statement, lineAdjustment);
    }

    private Expression branchExpression(Node node) {
        if (node instanceof IfStmt) {
            return ((IfStmt) node).getCondition();
        }
        if (node instanceof DoStmt) {
            return ((DoStmt) node).getCondition();
        }
        if (node instanceof SwitchStmt) {
            return ((SwitchStmt) node).getSelector();
        }
        if (node instanceof SwitchExpr) {
            return ((SwitchExpr) node).getSelector();
        }
        if (node instanceof WhileStmt) {
            return ((WhileStmt) node).getCondition();
        }
        if (node instanceof ForStmt && ((ForStmt) node).getCompare().isPresent()) {
            return ((ForStmt) node).getCompare().get();
        }
        if (node instanceof ForEachStmt) {
            return ((ForEachStmt) node).getIterable();
        }
        if (node instanceof ConditionalExpr) {
            return ((ConditionalExpr) node).getCondition();
        }
        return null;
    }

    private int beginLine(Node node, int lineAdjustment) {
        if (!node.getRange().isPresent()) {
            return -1;
        }
        return node.getRange().get().begin.line + lineAdjustment;
    }

    private void addLine(Set<Integer> lines, int line) {
        if (line > 0) {
            lines.add(Integer.valueOf(line));
        }
    }

    private void addNonEmpty(Set<String> values, String value) {
        if (value != null && !value.trim().isEmpty()) {
            values.add(value.trim());
        }
    }

    private Set<String> tokens(String text) {
        Set<String> tokens = new LinkedHashSet<String>();
        String normalized = lower(text).replaceAll("[^a-z0-9_]+", " ");
        String[] parts = normalized.split("\\s+");
        for (String part : parts) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private String inferOutcome(Set<String> scenarioTokens, String conditionText) {
        String condition = lower(conditionText);
        if (mentionsAny(scenarioTokens, "false", "else", "otherwise", "small", "smaller", "low", "lower",
                "less", "below", "under", "zero", "negative", "invalid", "empty", "missing", "absent",
                "disabled", "fail", "failure")) {
            if (condition.contains("== null") || condition.contains(".isempty()") || condition.contains("isblank()")) {
                return ScoutBranchMapping.TRUE;
            }
            return ScoutBranchMapping.FALSE;
        }
        if (mentionsAny(scenarioTokens, "true", "then", "large", "larger", "high", "higher", "greater",
                "above", "over", "more", "positive", "valid", "present", "exists", "enabled", "success")) {
            if (condition.contains("!= null") || condition.contains("!")) {
                return ScoutBranchMapping.TRUE;
            }
            return ScoutBranchMapping.TRUE;
        }
        if (mentionsAny(scenarioTokens, "null")) {
            if (condition.contains("== null")) {
                return ScoutBranchMapping.TRUE;
            }
            if (condition.contains("!= null")) {
                return ScoutBranchMapping.FALSE;
            }
        }
        return ScoutBranchMapping.UNKNOWN;
    }

    private boolean mentionsAny(Set<String> tokens, String... candidates) {
        for (String candidate : candidates) {
            if (tokens.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private static Set<String> shortStopwords() {
        Set<String> stopwords = new LinkedHashSet<String>();
        stopwords.add("a");
        stopwords.add("an");
        stopwords.add("as");
        stopwords.add("at");
        stopwords.add("be");
        stopwords.add("by");
        stopwords.add("do");
        stopwords.add("go");
        stopwords.add("if");
        stopwords.add("in");
        stopwords.add("is");
        stopwords.add("it");
        stopwords.add("no");
        stopwords.add("of");
        stopwords.add("ok");
        stopwords.add("on");
        stopwords.add("or");
        stopwords.add("so");
        stopwords.add("to");
        stopwords.add("up");
        return stopwords;
    }

    private static class BranchHint {
        private final String hintText;
        private final String searchText;
        private final int line;
        private final int trueBranchFlagLine;
        private final Influence influence;

        private BranchHint(String hintText, String searchText, int line, int trueBranchFlagLine, Influence influence) {
            this.hintText = hintText;
            this.searchText = searchText;
            this.line = line;
            this.trueBranchFlagLine = trueBranchFlagLine;
            this.influence = influence == null ? new Influence() : influence;
        }
    }

    private static class Influence {
        private final Set<String> parameters = new LinkedHashSet<String>();
        private final Set<String> fields = new LinkedHashSet<String>();
        private final Set<String> calls = new LinkedHashSet<String>();

        private void merge(Influence other) {
            if (other == null) {
                return;
            }
            parameters.addAll(other.parameters);
            fields.addAll(other.fields);
            calls.addAll(other.calls);
        }

        private String searchText() {
            StringBuilder builder = new StringBuilder();
            append(builder, parameters);
            append(builder, fields);
            append(builder, calls);
            return builder.toString();
        }

        private void append(StringBuilder builder, Set<String> values) {
            for (String value : values) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(value);
            }
        }
    }

    private static class ParsedSource {
        private final CompilationUnit compilationUnit;
        private final int lineAdjustment;

        private ParsedSource(CompilationUnit compilationUnit, int lineAdjustment) {
            this.compilationUnit = compilationUnit;
            this.lineAdjustment = lineAdjustment;
        }
    }

    private static class CallHint {
        private final String dependencyText;
        private final String searchText;
        private final int line;

        private CallHint(String dependencyText, String searchText, int line) {
            this.dependencyText = dependencyText;
            this.searchText = searchText;
            this.line = line;
        }
    }
}
