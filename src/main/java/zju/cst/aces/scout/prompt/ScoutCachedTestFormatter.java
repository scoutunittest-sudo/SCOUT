package zju.cst.aces.scout.prompt;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

class ScoutCachedTestFormatter {
    private static final int MAX_SNIPPETS = 5;
    private static final int MAX_LINES = 30;
    private final JavaParser javaParser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));

    String format(List<String> cachedTests) {
        if (cachedTests == null || cachedTests.isEmpty()) {
            return "";
        }

        Set<String> snippets = new LinkedHashSet<>();
        for (String cachedTest : cachedTests) {
            if (cachedTest == null || cachedTest.trim().isEmpty()) {
                continue;
            }
            List<String> extractedSnippets = extractSnippets(cachedTest);
            if (extractedSnippets.isEmpty()) {
                snippets.add(cachedTest.trim());
            } else {
                snippets.addAll(extractedSnippets);
            }
            if (snippets.size() >= MAX_SNIPPETS) {
                break;
            }
        }
        return joinLimited(snippets);
    }

    private List<String> extractSnippets(String cachedTest) {
        List<String> snippets = new ArrayList<>();
        ParseResult<CompilationUnit> result = parseCachedTest(cachedTest);
        if (!result.isSuccessful() || !result.getResult().isPresent()) {
            return snippets;
        }

        CompilationUnit compilationUnit = result.getResult().get();
        snippets.addAll(extractFieldSnippets(compilationUnit));

        List<MethodDeclaration> methods = compilationUnit.findAll(MethodDeclaration.class);
        for (MethodDeclaration method : methods) {
            if (!isRelevantMethod(method) || !method.getBody().isPresent()) {
                continue;
            }
            for (Statement statement : method.getBody().get().getStatements()) {
                String text = normalize(statement);
                if (isConstructorSetup(text)) {
                    snippets.add(text);
                }
            }
        }
        return snippets;
    }

    private ParseResult<CompilationUnit> parseCachedTest(String cachedTest) {
        for (String candidate : Arrays.asList(cachedTest,
                "class ScoutCachedTestWrapper {\n" + cachedTest + "\n}",
                "class ScoutCachedTestWrapper {\n@org.junit.jupiter.api.Test\nvoid cached() {\n" + cachedTest + "\n}\n}")) {
            ParseResult<CompilationUnit> result = javaParser.parse(candidate);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                return result;
            }
        }
        return javaParser.parse(cachedTest);
    }

    private List<String> extractFieldSnippets(CompilationUnit compilationUnit) {
        List<String> snippets = new ArrayList<>();
        for (FieldDeclaration field : compilationUnit.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator variable : field.getVariables()) {
                String text = field.getElementType().asString() + " " + normalize(variable) + ";";
                if (isConstructorSetup(text)) {
                    snippets.add(text);
                }
            }
        }
        return snippets;
    }

    private boolean isRelevantMethod(MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(annotation -> {
            String name = annotation.getNameAsString();
            return isRelevantAnnotation(name);
        });
    }

    private boolean isRelevantAnnotation(String name) {
        return "Test".equals(name)
                || "ParameterizedTest".equals(name)
                || "BeforeEach".equals(name)
                || "BeforeAll".equals(name)
                || "Before".equals(name)
                || "BeforeClass".equals(name)
                || name.endsWith(".Test")
                || name.endsWith(".ParameterizedTest")
                || name.endsWith(".BeforeEach")
                || name.endsWith(".BeforeAll")
                || name.endsWith(".Before")
                || name.endsWith(".BeforeClass");
    }

    private String normalize(Statement statement) {
        return statement == null ? "" : statement.toString().replaceAll("\\s+", " ").trim();
    }

    private String normalize(VariableDeclarator variable) {
        return variable == null ? "" : variable.toString().replaceAll("\\s+", " ").trim();
    }

    private boolean isConstructorSetup(String statement) {
        String lower = statement.toLowerCase();
        return statement.startsWith("new ")
                || statement.contains(" new ")
                || statement.contains("= new ")
                || statement.contains(".builder(")
                || statement.contains("builder()")
                || lower.contains("mock(")
                || lower.contains("spy(")
                || lower.contains("when(")
                || lower.contains("given(")
                || lower.contains("doreturn(")
                || lower.contains("dothrow(")
                || lower.contains("mockstatic(")
                || lower.contains("mockconstruction(");
    }

    private String joinLimited(Set<String> snippets) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        int lines = 0;
        for (String snippet : snippets) {
            if (snippet == null || snippet.isEmpty()) {
                continue;
            }
            if (count >= MAX_SNIPPETS || lines >= MAX_LINES) {
                break;
            }
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append(snippet);
            count++;
            lines++;
        }
        return builder.toString();
    }
}
