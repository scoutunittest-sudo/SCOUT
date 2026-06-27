package zju.cst.aces.scout.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ScoutUncoveredRegionMapper {
    public List<ScoutUncoveredRegion> map(String sourceCode, List<Integer> uncoveredLines) {
        if (uncoveredLines == null || uncoveredLines.isEmpty()) {
            return Collections.emptyList();
        }

        String source = sourceCode == null ? "" : sourceCode;
        CompilationUnit compilationUnit;
        try {
            compilationUnit = StaticJavaParser.parse(source);
        } catch (RuntimeException e) {
            return fallbackRegions(source, uncoveredLines);
        }

        List<ScoutUncoveredRegion> regions = new ArrayList<ScoutUncoveredRegion>();
        for (Integer uncoveredLine : uncoveredLines) {
            if (uncoveredLine == null) {
                continue;
            }
            int line = uncoveredLine.intValue();
            regions.add(mapLine(source, compilationUnit, line));
        }
        return regions;
    }

    private ScoutUncoveredRegion mapLine(String source, CompilationUnit compilationUnit, int line) {
        try {
            Node smallestNode = findSmallestNodeContainingLine(compilationUnit, line);
            Node regionNode = findNearestRegionNode(smallestNode);
            if (regionNode == null) {
                return lineRegion(source, line);
            }
            return new ScoutUncoveredRegion(line, kindOf(regionNode), regionNode.toString().trim());
        } catch (RuntimeException e) {
            return lineRegion(source, line);
        }
    }

    private Node findSmallestNodeContainingLine(CompilationUnit compilationUnit, int line) {
        List<Node> nodes = compilationUnit.findAll(Node.class);
        List<Node> containingNodes = new ArrayList<Node>();
        for (Node node : nodes) {
            if (containsLine(node, line)) {
                containingNodes.add(node);
            }
        }
        if (containingNodes.isEmpty()) {
            return null;
        }
        Collections.sort(containingNodes, new Comparator<Node>() {
            @Override
            public int compare(Node first, Node second) {
                int firstLineSpan = lineSpan(first);
                int secondLineSpan = lineSpan(second);
                if (firstLineSpan != secondLineSpan) {
                    return firstLineSpan - secondLineSpan;
                }
                return columnSpan(first) - columnSpan(second);
            }
        });
        return containingNodes.get(0);
    }

    private boolean containsLine(Node node, int line) {
        if (!node.getRange().isPresent()) {
            return false;
        }
        return node.getRange().get().begin.line <= line && line <= node.getRange().get().end.line;
    }

    private int lineSpan(Node node) {
        if (!node.getRange().isPresent()) {
            return Integer.MAX_VALUE;
        }
        return node.getRange().get().end.line - node.getRange().get().begin.line;
    }

    private int columnSpan(Node node) {
        if (!node.getRange().isPresent()) {
            return Integer.MAX_VALUE;
        }
        return node.getRange().get().end.column - node.getRange().get().begin.column;
    }

    private Node findNearestRegionNode(Node node) {
        Node current = node;
        while (current != null) {
            if (isRegionNode(current)) {
                return current;
            }
            current = current.getParentNode().orElse(null);
        }
        return null;
    }

    private boolean isRegionNode(Node node) {
        return node instanceof IfStmt
                || node instanceof WhileStmt
                || node instanceof ForStmt
                || node instanceof ForEachStmt
                || node instanceof SwitchStmt
                || node instanceof SwitchEntry
                || node instanceof ConditionalExpr;
    }

    private String kindOf(Node node) {
        if (node instanceof IfStmt) {
            return "if";
        }
        if (node instanceof WhileStmt) {
            return "while";
        }
        if (node instanceof ForStmt) {
            return "for";
        }
        if (node instanceof ForEachStmt) {
            return "enhanced_for";
        }
        if (node instanceof SwitchStmt || node instanceof SwitchEntry) {
            return "switch";
        }
        if (node instanceof ConditionalExpr) {
            return "ternary";
        }
        return "line";
    }

    private List<ScoutUncoveredRegion> fallbackRegions(String source, List<Integer> uncoveredLines) {
        List<ScoutUncoveredRegion> regions = new ArrayList<ScoutUncoveredRegion>();
        for (Integer uncoveredLine : uncoveredLines) {
            if (uncoveredLine == null) {
                continue;
            }
            int line = uncoveredLine.intValue();
            regions.add(lineRegion(source, line));
        }
        return regions;
    }

    private ScoutUncoveredRegion lineRegion(String source, int line) {
        return new ScoutUncoveredRegion(line, "line", lineSnippet(source, line));
    }

    private String lineSnippet(String source, int line) {
        String[] lines = source.split("\\r\\n|\\n|\\r", -1);
        if (line < 1 || line > lines.length) {
            return "";
        }
        return lines[line - 1].trim();
    }
}
