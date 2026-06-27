package zju.cst.aces.scout.analysis;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScoutUncoveredRegion {
    private int line;
    private String kind;
    private String code;
}
