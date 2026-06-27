package zju.cst.aces.util;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.dto.TestMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @ClassName TestProcessor
 * @Description Process generated tests, remove the error test case in the test class
 */

//TODO: remove correct test case in the repair prompt.
public class TestProcessor {
    private static final JavaParser parser = new JavaParser();
    private static final Pattern ERROR_LINE_PATTERN = Pattern.compile("\\bline\\s+(-?\\d+)\\b");
    private String fullTestName;

    public TestProcessor(String fullTestName) {
        this.fullTestName = fullTestName;
    }

    public List<Integer> getErrorLineNum(TestExecutionSummary summary) {
        List<Integer> errorLineNum = new ArrayList<>();
        summary.getFailures().forEach(failure -> {
            for (StackTraceElement st : failure.getException().getStackTrace()) {
                if (st.getClassName().contains(fullTestName)) {
                    int lineNum = st.getLineNumber();
                    errorLineNum.add(lineNum);
                }
            }
        });
        return errorLineNum;
    }

    public boolean containError(List<Integer> errorLineNum, MethodDeclaration method) {
        int beginPosition = method.getBegin().get().line;
        int endPosition = method.getEnd().get().line;
        return !errorLineNum.stream().filter(lineNum -> lineNum >= beginPosition && lineNum <= endPosition)
                .collect(Collectors.toList()).isEmpty();
    }

    public boolean isTestCase(MethodDeclaration method) {
        return !method.getAnnotations().stream().filter(annotationExpr -> {
            String annotationName = annotationExpr.getNameAsString();
            return annotationName.equals("Test")
                    || annotationName.equals("ParameterizedTest")
                    || annotationName.endsWith(".Test")
                    || annotationName.endsWith(".ParameterizedTest");
        }).collect(Collectors.toList()).isEmpty();
    }

    public String removeErrorTest(PromptInfo promptInfo, TestExecutionSummary summary) {
        String result = promptInfo.getUnitTest();
        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(result);
            CompilationUnit cu = parseResult.getResult().orElseThrow(()->new NoSuchElementException("CompilationUnit not present in parse result"));
            List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
            List<Integer> errorLineNum = getErrorLineNum(summary);
            for (MethodDeclaration method : methods) {
                //TODO: remove error test case and other methods with errors
                if (containError(errorLineNum, method)) {
                    method.remove();
                }
            }
            if (cu.findAll(MethodDeclaration.class).stream().filter(this::isTestCase).collect(Collectors.toList()).isEmpty()) {
                return null;
            }
            result = cu.toString();
        } catch (Exception e) {
            System.out.println("In TestProcessor.removeErrorTest: " + e);
            return null;
        }
        return result;
    }

    public String removeErrorTestMethods(PromptInfo promptInfo, TestExecutionSummary summary) {
        return removeErrorTestMethodsByLineNumbers(promptInfo, getErrorLineNum(summary));
    }

    public String removeErrorTestMethods(PromptInfo promptInfo, TestMessage errorMsg) {
        return removeErrorTestMethodsByLineNumbers(promptInfo, getErrorLineNum(errorMsg));
    }

    private String removeErrorTestMethodsByLineNumbers(PromptInfo promptInfo, List<Integer> errorLineNum) {
        if (promptInfo == null || errorLineNum == null || errorLineNum.isEmpty()) {
            return null;
        }
        String result = promptInfo.getUnitTest();
        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(result);
            CompilationUnit cu = parseResult.getResult().orElseThrow(()->new NoSuchElementException("CompilationUnit not present in parse result"));
            List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
            boolean removed = false;
            for (MethodDeclaration method : methods) {
                if (isTestCase(method) && containError(errorLineNum, method)) {
                    method.remove();
                    removed = true;
                }
            }
            if (!removed) {
                return null;
            }
            if (cu.findAll(MethodDeclaration.class).stream().filter(this::isTestCase).collect(Collectors.toList()).isEmpty()) {
                return null;
            }
            return cu.toString();
        } catch (Exception e) {
            System.out.println("In TestProcessor.removeErrorTestMethods: " + e);
            return null;
        }
    }

    private List<Integer> getErrorLineNum(TestMessage errorMsg) {
        List<Integer> errorLineNum = new ArrayList<>();
        if (errorMsg == null || errorMsg.getErrorMessage() == null) {
            return errorLineNum;
        }
        for (String error : errorMsg.getErrorMessage()) {
            Matcher matcher = ERROR_LINE_PATTERN.matcher(error == null ? "" : error);
            while (matcher.find()) {
                int lineNum = Integer.parseInt(matcher.group(1));
                if (lineNum > 0) {
                    errorLineNum.add(lineNum);
                }
            }
        }
        return errorLineNum;
    }

    public String removeCorrectTest(PromptInfo promptInfo, TestExecutionSummary summary) {
        String result = promptInfo.getUnitTest();
        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(result);
            CompilationUnit cu = parseResult.getResult().orElseThrow(()->new NoSuchElementException("CompilationUnit not present in parse result"));
            List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
            List<Integer> errorLineNum = getErrorLineNum(summary);
            for (MethodDeclaration method : methods) {
                if (!containError(errorLineNum, method) && isTestCase(method)) {
                    promptInfo.addCorrectTest(method);
                    method.remove();
                }
            }
            result = cu.toString();
            if (cu.findAll(MethodDeclaration.class).stream().filter(this::isTestCase).collect(Collectors.toList()).isEmpty()) {
//                throw new Exception("In TestProcessor.removeCorrectTest: No test case left");
                System.out.println("In TestProcessor.removeCorrectTest: No test case left");
            }
        } catch (Exception e) {
            System.out.println("In TestProcessor.removeCorrectTest: " + e);
        }
        promptInfo.setUnitTest(result);
        return result;
    }

    public String addCorrectTest(PromptInfo promptInfo) {
        String result = promptInfo.getUnitTest();
        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(result);
            CompilationUnit cu = parseResult.getResult().orElseThrow(()->new NoSuchElementException("CompilationUnit not present in parse result"));
            promptInfo.getCorrectTests().keySet().forEach(className -> {
                cu.getClassByName(className).ifPresent(classOrInterfaceDeclaration -> {
                    promptInfo.getCorrectTests().get(className).forEach(methodDeclaration -> {
                        if (classOrInterfaceDeclaration.getMethodsByName(methodDeclaration.getNameAsString()).isEmpty()) {
                            classOrInterfaceDeclaration.addMember(methodDeclaration);
                        }
                    });
                });
            });
            promptInfo.getCorrectTests().clear();
            result = cu.toString();
        } catch (Exception e) {
            System.out.println("In TestProcessor.addCorrectTest: " + e);
        }
        promptInfo.setUnitTest(result);
        return result;
    }
}
