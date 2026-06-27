package zju.cst.aces.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.types.ResolvedType;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import slicing.graphs.CallGraph;
import slicing.graphs.CallGraph.Edge;
import slicing.graphs.CallGraph.Vertex;
import slicing.graphs.jsysdg.JSysDG;
import slicing.graphs.sdg.SDG;
import slicing.slicing.MultiVariableCriterion;
import slicing.slicing.Slice;
import zju.cst.aces.api.Project;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.MethodExampleMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class ProjectParser {

    public static JavaParser parser;
    public Path srcFolderPath;
    public Path outputPath;
    public Map<String, Set<String>> classNameMap = new HashMap<>();
    public static Config config;
    public int classCount = 0;
    public int methodCount = 0;

    public ProjectParser(Config config) {
        this.srcFolderPath = Paths.get(config.getProject().getBasedir().getAbsolutePath(), "src", "main", "java");
        this.config = config;
        this.outputPath = config.getParseOutput();
        this.parser = config.getParser();
    }

    /**
     * Parse the project.
     */
    public void parse() {
        List<String> classPaths = scanSourceDirectory(config.getProject());
        if (classPaths.isEmpty()) {
            config.getLogger().warn("No java file found in " + srcFolderPath);
            return;
        }
        NodeList<CompilationUnit> cus = new NodeList<>();
        for (String classPath : classPaths) {
            File file = new File(classPath);
            try {
                ParseResult<CompilationUnit> parseResult = parser.parse(file);
                CompilationUnit cu = parseResult.getResult()
                        .orElseThrow(() -> new NoSuchElementException("No result present in parseResult"));

                cus.add(cu);
            } catch (Exception e) {
                throw new RuntimeException("In ProjectParser.parse: " + e);
            }
        }
//      MethodExampleMap methodExampleMap = createMethodExampleMap(cus);

        for (CompilationUnit cu : cus) {
            try {
                Path output = outputPath;
                String packageName = "";
                if (cu.getPackageDeclaration().isPresent()) {
                    packageName = cu.getPackageDeclaration().get().getNameAsString();
                    output = outputPath.resolve(packageName.replace(".", File.separator));
                }
                ClassParser classParser = new ClassParser(parser, config.getProject(), output,
                        config.getLogger(),  config.getGSON(), config.sharedInteger, config.classMapping, config.ocm);
                int classNum = classParser.extractClass(cu);

                if (classNum == 0) {
                    continue;
                }
                addClassMap(cu);
                classCount += classNum;
                methodCount += classParser.methodCount;
            } catch (Exception e) {
                throw new RuntimeException("In ProjectParser.parse: " + e);
            }
        }
        exportClassMapping();
//      exportOCC();
//      exportMethodExampleMap(methodExampleMap);
        exportJson(config.getClassNameMapPath(), classNameMap);
        config.getLogger().info("\nParsed classes: " + classCount + "\nParsed methods: " + methodCount);
    }

    /**
     * Parse the selected class of the project.
     */
    public void parseClass(String cname) {
        List<String> classPaths = scanSourceDirectory(config.getProject());
        if (classPaths.isEmpty()) {
            config.getLogger().warn("No java file found in " + srcFolderPath);
            return;
        }
        NodeList<CompilationUnit> cus = new NodeList<>();
        String tmpCname = cname.replace(".", "/");

        for (String classPath : classPaths) {
            File file = new File(classPath);
            if (!classPath.contains(tmpCname)) {
                continue;
            }
            try {
                ParseResult<CompilationUnit> parseResult = parser.parse(file);
                CompilationUnit cu = parseResult.getResult()
                        .orElseThrow(() -> new NoSuchElementException("No result present in parseResult"));

                cus.add(cu);
            } catch (Exception e) {
                throw new RuntimeException("In ProjectParser.parse: " + e);
            }
        }
//      MethodExampleMap methodExampleMap = createMethodExampleMap(cus);

        for (CompilationUnit cu : cus) {
            try {
                Path output = outputPath;
                String packageName = "";
                if (cu.getPackageDeclaration().isPresent()) {
                    packageName = cu.getPackageDeclaration().get().getNameAsString();
                    output = outputPath.resolve(packageName.replace(".", File.separator));
                }
                ClassParser classParser = new ClassParser(parser, config.getProject(), output,
                        config.getLogger(),  config.getGSON(), config.sharedInteger, config.classMapping, config.ocm);
                int classNum = classParser.extractClass(cu);

                if (classNum == 0) {
                    continue;
                }
                addClassMap(cu);
                classCount += classNum;
                methodCount += classParser.methodCount;
            } catch (Exception e) {
                throw new RuntimeException("In ProjectParser.parse: " + e);
            }
        }
        exportClassMapping();
//      exportOCC();
//      exportMethodExampleMap(methodExampleMap);
        exportJson(config.getClassNameMapPath(), classNameMap);
        config.getLogger().info("\nParsed classes: " + classCount + "\nParsed methods: " + methodCount);
    }

    private SDG createSDG(NodeList<CompilationUnit> cus) {
        SDG sdg = new JSysDG();
        sdg.build(cus);
        return sdg;
    }

    private MethodExampleMap createMethodExampleMap(NodeList<CompilationUnit> cus) {
        config.getLogger().info("Starting to create method example map... - ProjectParser");
        MethodExampleMap methodExampleMap = new MethodExampleMap();
        SDG sdg = createSDG(cus);

        AtomicInteger cuIndex = new AtomicInteger();
        AtomicInteger methodIndex = new AtomicInteger();
        int totalMethods = slicing.graphs.ClassGraph.getInstance().getMethodDeclarationMap().size();
        cus.forEach(cu -> {
            cu.findAll(CallableDeclaration.class).forEach(callable -> {
                Set<Edge<?>> edges = findEdgeByCallGraph(callable, sdg.getCallGraph());
                if (!edges.isEmpty()) {
                    config.getLogger().info("Processing method: [ " + callable.getNameAsString() + " ]" + " total: " + methodIndex.getAndIncrement() + " / " + totalMethods);
                    edges.forEach(edge -> {
                        config.getLogger().info("Processing edge: " + edge.getSource().getNameAsString() + " -> " + edge.getTarget().getNameAsString());
                        if (edge.getTarget().equals(callable)) {
                            if (! (edge.getCall() instanceof Expression)) {
                                return;
                            }
                            Expression callSite = (Expression) edge.getCall();
                            int callSiteLine = callSite.getBegin().orElse(new Position(0, 0)).line;
                            List<String> arguments = createStringArgumets(callSite);

                            CallableDeclaration<?> caller = edge.getSource();
                            CompilationUnit callerCompilationUnit = findClassByCallable(caller);
                            String callerClassFullName = callerCompilationUnit.getType(0).getFullyQualifiedName().get();

                            if (!arguments.isEmpty()) {
                                MultiVariableCriterion sc = new MultiVariableCriterion(callerClassFullName, callSiteLine, arguments);
                                config.getLogger().info("Slicing method: " + getSignatureByCallable(callable) + " at callsite: < " + callSite + " >");
                                Slice slice = sdg.slice(sc);
                                if (!slice.toAst().isEmpty()) {
                                    String code = findCodeBySlice(slice, callerCompilationUnit.getType(0).getNameAsString());
                                    if (code != null) {
                                        methodExampleMap.add(getQualifiedSignatureByCallable(callable),
                                                callerClassFullName,
                                                getSignatureByCallable(caller),
                                                callSiteLine,
                                                code);
                                    }
                                }
                            }
                        }
                    });
                }
            });
        });
        return methodExampleMap;
    }

    private String findCodeBySlice(Slice slice, String callerClassFullName) {
        for (CompilationUnit cu : slice.toAst()) {
            if (!"".equals(cu.toString()) && cu.getType(0).getNameAsString().equals(callerClassFullName)) {
                return cu.toString();
            }
        }
        return null;
    }


    private List<String> createStringArgumets(Expression callSite) {
        Set<String> arguments = new HashSet<>();
        if (!isPrimitiveOrString(callSite) && (callSite.isNameExpr() || callSite.isThisExpr())) {
            arguments.add(callSite.toString());
        } else if (callSite.isMethodCallExpr()) {
            callSite.asMethodCallExpr().getArguments().forEach(arg -> {
                    arguments.addAll(createStringArgumets(arg));
            });
            if (callSite.hasScope()) {
                arguments.addAll(createStringArgumets(callSite.asMethodCallExpr().getScope().get()));
            }
        } else if (callSite.isObjectCreationExpr()) {
            callSite.asObjectCreationExpr().getArguments().forEach(arg -> {
                    arguments.addAll(createStringArgumets(arg));
            });
            if (callSite.hasScope()) {
                arguments.addAll(createStringArgumets(callSite.asObjectCreationExpr().getScope().get()));
            }
        } else if (callSite.isFieldAccessExpr())  {
            arguments.addAll(createStringArgumets(callSite.asFieldAccessExpr().getNameAsExpression()));
            if (callSite.hasScope()) {
                arguments.addAll(createStringArgumets(callSite.asFieldAccessExpr().getScope()));
            }
        }
//        else {
//            throw new RuntimeException("Unsupported call site type: " + callSite.getClass().getSimpleName());
//        }
        return new ArrayList<>(arguments);
    }

    private boolean isPrimitiveOrString(Expression expr) {
        if (expr.isNameExpr()) {
            try {
                ResolvedType type = expr.asNameExpr().resolve().getType();
                if (type.isPrimitive()) {
                    return true;
                }
                if (type.isReferenceType() && type.asReferenceType().getQualifiedName().equals("java.lang.String")) {
                    return true;
                }
            } catch (UnsolvedSymbolException | IllegalStateException e) {
                return true; // ignore unsolved symbol
            }
        }
        return false;
    }

    private String getSignatureByCallable(CallableDeclaration<?> callable) {
        if (callable.isMethodDeclaration()) {
            return callable.asMethodDeclaration().resolve().getSignature();
        } else if (callable.isConstructorDeclaration()) {
            return callable.asConstructorDeclaration().resolve().getSignature();
        } else {
            throw new RuntimeException("Unsupported callable type: " + callable.getClass().getSimpleName());
        }
    }

    private String getQualifiedSignatureByCallable(CallableDeclaration<?> callable) {
        if (callable.isMethodDeclaration()) {
            MethodDeclaration md = callable.asMethodDeclaration();
            return md.resolve().getQualifiedSignature()
                    .replace(md.resolve().getSignature(), md.getSignature().asString());
        } else if (callable.isConstructorDeclaration()) {
            ConstructorDeclaration cd = callable.asConstructorDeclaration();
            return cd.resolve().getQualifiedSignature()
                    .replace(cd.resolve().getSignature(), cd.getSignature().asString());
        } else {
            throw new RuntimeException("Unsupported callable type: " + callable.getClass().getSimpleName());
        }
    }

    private CompilationUnit findClassByCallable(CallableDeclaration<?> node) {
        CompilationUnit cu = node.findAncestor(CompilationUnit.class)
                .orElseThrow(() -> new NoSuchElementException("No CompilationUnit ancestor found for node"));

        return cu;

    }

    private Set<Edge<?>> findEdgeByCallGraph(CallableDeclaration node, CallGraph callGraph) {
        Vertex ver = new Vertex(node);
        return callGraph.edgesOf(ver);
    }

    private List<CallableDeclaration<?>> findCallerByCallGraph(CallableDeclaration node, CallGraph callGraph) {
        List<CallableDeclaration<?>> callerList = new ArrayList<>();
        Vertex ver = new Vertex(node);
        callGraph.edgesOf(ver).forEach(edge -> {
            if (edge.getTarget().equals(node)) {
                System.out.println("find caller: " + edge.getSource());
                callerList.add(edge.getSource());
            }
        });
        return callerList;
    }

    public void addClassMap(CompilationUnit cu) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classNode -> {
            String className = classNode.getNameAsString();
            String fullClassName = cu.getPackageDeclaration().isPresent() ?
                    cu.getPackageDeclaration().get().getNameAsString() + "." + className : className;
            if (classNameMap.containsKey(className)) {
                classNameMap.get(className).add(fullClassName);
            } else {
                Set<String> fullClassNames = new HashSet<>();
                fullClassNames.add(fullClassName);
                classNameMap.put(className, fullClassNames);
            }
        });
    }

    public static void exportJson(Path path, Object obj) {
        if(Files.exists(path)){
            return;
        }
        if (!Files.exists(path.getParent())) {
            try {
                Files.createDirectories(path.getParent());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(path.toFile()), StandardCharsets.UTF_8)){
            writer.write(config.getGSON().toJson(obj));
        } catch (Exception e) {
            throw new RuntimeException("In ProjectParser.exportJson: " + e);
        }
    }

    public static List<String> scanSourceDirectory(Project project) {
        Set<String> classPaths = new LinkedHashSet<>();
        for (String sourceRoot : sourceRoots(project)) {
            if (sourceRoot == null || sourceRoot.trim().isEmpty()) {
                continue;
            }
            Path root = Paths.get(sourceRoot);
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(path -> classPaths.add(path.toString()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return new ArrayList<>(classPaths);
    }

    public static List<String> scanProjectClasses(Project project) {
        Set<String> classNames = new LinkedHashSet<>();
        for (String sourceRoot : sourceRoots(project)) {
            if (sourceRoot == null || sourceRoot.trim().isEmpty()) {
                continue;
            }
            Path root = Paths.get(sourceRoot);
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted(Comparator.comparing(Path::toString))
                        .map(path -> toFullyQualifiedClassName(root, path))
                        .filter(className -> !className.isEmpty())
                        .forEach(classNames::add);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return new ArrayList<>(classNames);
    }

    private static List<String> sourceRoots(Project project) {
        List<String> sourceRoots = project.getCompileSourceRoots();
        if ((sourceRoots == null || sourceRoots.isEmpty()) && project.getBasedir() != null) {
            sourceRoots = Collections.singletonList(Paths.get(project.getBasedir().getAbsolutePath(),
                    "src", "main", "java").toString());
        }
        if (sourceRoots == null) {
            return Collections.emptyList();
        }
        return sourceRoots;
    }

    private static String toFullyQualifiedClassName(Path sourceRoot, Path javaSourcePath) {
        Path relativePath = sourceRoot.relativize(javaSourcePath);
        String sourceName = relativePath.toString();
        if (!sourceName.endsWith(".java")) {
            return "";
        }
        return sourceName.substring(0, sourceName.length() - ".java".length())
                .replace('/', '.')
                .replace('\\', '.');
    }

    public static void walkDep(DependencyNode node, Set<DependencyNode> depSet) {
        depSet.add(node);
        for (DependencyNode dep : node.getChildren()) {
            walkDep(dep, depSet);
        }
    }

    public void exportClassMapping() {
        Path savePath = config.tmpOutput.resolve("classMapping.json");
        exportJson(savePath, config.classMapping);
    }

    public void exportOCC() {
        Path savePath = config.tmpOutput.resolve("objectConstructionCode.json");
        exportJson(savePath, config.ocm.getOCM());
    }

    public void exportMethodExampleMap(MethodExampleMap methodExampleMap) {
        Path savePath = config.tmpOutput.resolve("methodExampleCode.json");
        exportJson(savePath, methodExampleMap.getMEM());
    }

    public static void setLanguageLevel(ParserConfiguration configuration) {
        String versionStr = System.getProperty("java.version");
        int version = parseJavaVersion(versionStr);

//        int versionPrefix = Integer.parseInt(System.getProperty("java.version").split("\\.")[0]);
        switch (version) {
            case 8: // java 8
                configuration.setLanguageLevel(LanguageLevel.JAVA_8);
                break;
            case 9:
                configuration.setLanguageLevel(LanguageLevel.JAVA_9);
                break;
            case 10:
                configuration.setLanguageLevel(LanguageLevel.JAVA_10);
                break;
            case 11:
                configuration.setLanguageLevel(LanguageLevel.JAVA_11);
                break;
            case 12:
                configuration.setLanguageLevel(LanguageLevel.JAVA_12);
                break;
            case 13:
                configuration.setLanguageLevel(LanguageLevel.JAVA_13);
                break;
            case 14:
                configuration.setLanguageLevel(LanguageLevel.JAVA_14);
                break;
            case 15:
                configuration.setLanguageLevel(LanguageLevel.JAVA_15);
                break;
            case 16:
                configuration.setLanguageLevel(LanguageLevel.JAVA_16);
                break;
            default:
                configuration.setLanguageLevel(LanguageLevel.JAVA_17);
                break;
        }
    }
    private static int parseJavaVersion(String versionStr) {
        String[] parts = versionStr.split("\\.");
        if (parts.length > 1) {
            try {
                // Major version
                return Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                // Handle error if version format is unexpected
                throw new IllegalArgumentException("Invalid Java version format: " + versionStr, e);
            }
        }
        // Fallback if version format is unexpected
        throw new IllegalArgumentException("Invalid Java version format: " + versionStr);
    }

}
