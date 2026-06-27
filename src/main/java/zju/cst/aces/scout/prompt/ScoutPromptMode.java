package zju.cst.aces.scout.prompt;

public enum ScoutPromptMode {
    INITIAL_NO_DEPS("scout_initial_no_deps.ftl"),
    INITIAL_WITH_DEPS("scout_initial_with_deps.ftl"),
    INITIAL_CONSTR_NO_DEPS("scout_initial_constr_no_deps.ftl"),
    INITIAL_CONSTR_WITH_DEPS("scout_initial_constr_with_deps.ftl"),
    CACHE_NO_DEPS("scout_cache_no_deps.ftl"),
    CACHE_WITH_DEPS("scout_cache_with_deps.ftl"),
    GUIDE_COVERAGE("scout_guide_coverage.ftl"),
    GUIDE_COVERAGE_NO_BRANCH("scout_guide_coverage_no_branch.ftl"),
    GUIDE_COVERAGE_CALLERS("scout_guide_coverage_callers.ftl"),
    ERROR("scout_error.ftl"),
    ERROR_CONSTR("scout_error_constr.ftl"),
    FAIL("scout_fail.ftl");

    private final String template;

    ScoutPromptMode(String template) {
        this.template = template;
    }

    public String getTemplate() {
        return template;
    }
}
