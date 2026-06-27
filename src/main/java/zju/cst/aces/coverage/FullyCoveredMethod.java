package zju.cst.aces.coverage;

import lombok.Data;

@Data
public class FullyCoveredMethod {
    private String className;
    private String methodName;
    private String methodSignature;
    private long lineCovered;
    private long lineTotal;
    private double lineCoverage;
    private int firstLine;
    private int lastLine;
}
