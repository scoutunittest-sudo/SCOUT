package zju.cst.aces.scout.description;

import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.runner.AbstractRunner;
import zju.cst.aces.scout.state.ScoutClassState;
import zju.cst.aces.scout.state.ScoutMethodState;
import zju.cst.aces.scout.state.ScoutProjectState;
import zju.cst.aces.scout.state.ScoutStateStore;
import zju.cst.aces.util.Counter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ScoutDescriptionPreloader {
    private final Config config;
    private final ScoutStateStore store;
    private final DescriptionService descriptionService;
    private final TargetLoader targetLoader;

    public ScoutDescriptionPreloader(Config config, ScoutStateStore store) {
        this(config, store,
                new RealDescriptionService(new ScoutDescriptionService(config, store)),
                new ParsedTargetLoader(config));
    }

    ScoutDescriptionPreloader(Config config, ScoutStateStore store,
                              DescriptionService descriptionService,
                              TargetLoader targetLoader) {
        this.config = config;
        this.store = store;
        this.descriptionService = descriptionService;
        this.targetLoader = targetLoader;
    }

    public void preload(List<String> fullClassNames) {
        if (store == null || descriptionService == null || targetLoader == null
                || fullClassNames == null || fullClassNames.isEmpty()) {
            return;
        }

        ScoutProjectState projectState = loadProjectState();
        descriptionService.ensureProjectDescription(projectState);

        List<ClassInfo> classInfos = targetLoader.loadClasses(fullClassNames);
        if (classInfos.isEmpty()) {
            return;
        }

        preloadClasses(projectState, classInfos);
        preloadMethods(projectState, classInfos);
    }

    private void preloadClasses(final ScoutProjectState projectState, List<ClassInfo> classInfos) {
        runInParallel(stageThreadCount(), classInfos,
                new ItemTask<ClassInfo>() {
                    @Override
                    public void run(ClassInfo classInfo) {
                        ScoutClassState classState = loadClassState(classInfo);
                        descriptionService.ensureClassDescription(classInfo, projectState, classState);
                    }
                });
    }

    private void preloadMethods(final ScoutProjectState projectState, List<ClassInfo> classInfos) {
        List<MethodTarget> methodTargets = new ArrayList<MethodTarget>();
        for (ClassInfo classInfo : classInfos) {
            ScoutClassState classState = loadClassState(classInfo);
            for (MethodInfo methodInfo : targetLoader.loadMethods(classInfo)) {
                methodTargets.add(new MethodTarget(classState, methodInfo));
            }
        }
        runInParallel(stageThreadCount(), methodTargets,
                new ItemTask<MethodTarget>() {
                    @Override
                    public void run(MethodTarget methodTarget) {
                        ScoutMethodState methodState = loadMethodState(methodTarget.classState, methodTarget.methodInfo);
                        descriptionService.ensureMethodDescription(methodTarget.methodInfo, projectState,
                                methodTarget.classState, methodState);
                    }
                });
    }

    private <T> void runInParallel(int threadCount, List<T> values, final ItemTask<T> task) {
        if (values == null || values.isEmpty()) {
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, threadCount));
        List<Future<Void>> futures = new ArrayList<Future<Void>>();
        for (final T value : values) {
            futures.add(executor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    if (config != null) {
                        config.markWorkerThreadStarted();
                    }
                    try {
                        task.run(value);
                        return null;
                    } finally {
                        if (config != null) {
                            config.markWorkerThreadFinished();
                        }
                    }
                }
            }));
        }
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                warn("SCOUT description preload interrupted: " + e.getMessage());
                break;
            } catch (ExecutionException e) {
                warn("SCOUT description preload failed: " + rootMessage(e));
            }
        }
        executor.shutdown();
    }

    private ScoutProjectState loadProjectState() {
        try {
            return store.loadProjectState();
        } catch (IOException e) {
            warn("SCOUT failed to load project state for preload: " + e.getMessage());
            return new ScoutProjectState();
        }
    }

    private ScoutClassState loadClassState(ClassInfo classInfo) {
        try {
            return store.loadClassState(classInfo == null ? "" : safe(classInfo.getFullClassName()));
        } catch (IOException e) {
            warn("SCOUT failed to load class state for preload: " + e.getMessage());
            return new ScoutClassState();
        }
    }

    private ScoutMethodState loadMethodState(ScoutClassState classState, MethodInfo methodInfo) {
        try {
            return store.loadMethodState(
                    classState == null ? "" : safe(classState.getFullClassName()),
                    methodInfo == null ? "" : safe(methodInfo.getMethodSignature()));
        } catch (IOException e) {
            warn("SCOUT failed to load method state for preload: " + e.getMessage());
            return new ScoutMethodState();
        }
    }

    private int stageThreadCount() {
        int configuredThreads = config == null ? 0 : config.getMaxThreads();
        return configuredThreads > 0 ? configuredThreads : Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    }

    private void warn(String message) {
        if (config != null && config.getLogger() != null) {
            config.getLogger().warn(message);
        }
    }

    private String rootMessage(ExecutionException e) {
        Throwable cause = e.getCause();
        return cause == null ? e.getMessage() : cause.getMessage();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    interface DescriptionService {
        void ensureProjectDescription(ScoutProjectState projectState);

        void ensureClassDescription(ClassInfo classInfo, ScoutProjectState projectState,
                                    ScoutClassState classState);

        void ensureMethodDescription(MethodInfo methodInfo, ScoutProjectState projectState,
                                     ScoutClassState classState, ScoutMethodState methodState);
    }

    interface TargetLoader {
        List<ClassInfo> loadClasses(List<String> fullClassNames);

        List<MethodInfo> loadMethods(ClassInfo classInfo);
    }

    private interface ItemTask<T> {
        void run(T value);
    }

    private static class MethodTarget {
        private final ScoutClassState classState;
        private final MethodInfo methodInfo;

        private MethodTarget(ScoutClassState classState, MethodInfo methodInfo) {
            this.classState = classState;
            this.methodInfo = methodInfo;
        }
    }

    private static class RealDescriptionService implements DescriptionService {
        private final ScoutDescriptionService delegate;

        private RealDescriptionService(ScoutDescriptionService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void ensureProjectDescription(ScoutProjectState projectState) {
            delegate.ensureProjectDescription(projectState);
        }

        @Override
        public void ensureClassDescription(ClassInfo classInfo, ScoutProjectState projectState,
                                           ScoutClassState classState) {
            delegate.ensureClassDescription(classInfo, projectState, classState);
        }

        @Override
        public void ensureMethodDescription(MethodInfo methodInfo, ScoutProjectState projectState,
                                            ScoutClassState classState, ScoutMethodState methodState) {
            delegate.ensureMethodDescription(methodInfo, projectState, classState, methodState);
        }
    }

    private static class ParsedTargetLoader implements TargetLoader {
        private final Config config;

        private ParsedTargetLoader(Config config) {
            this.config = config;
        }

        @Override
        public List<ClassInfo> loadClasses(List<String> fullClassNames) {
            if (fullClassNames == null || fullClassNames.isEmpty()) {
                return Collections.emptyList();
            }
            List<ClassInfo> classInfos = new ArrayList<ClassInfo>();
            for (String fullClassName : fullClassNames) {
                try {
                    ClassInfo classInfo = AbstractRunner.getClassInfo(config, fullClassName);
                    if (Counter.filter(classInfo)) {
                        classInfos.add(classInfo);
                    }
                } catch (IOException e) {
                    // Parsed class loading failures are reported by the main runner as well.
                }
            }
            return classInfos;
        }

        @Override
        public List<MethodInfo> loadMethods(ClassInfo classInfo) {
            if (classInfo == null || classInfo.getMethodSigs() == null || classInfo.getMethodSigs().isEmpty()) {
                return Collections.emptyList();
            }
            List<MethodInfo> methodInfos = new ArrayList<MethodInfo>();
            for (String methodSignature : classInfo.getMethodSigs().keySet()) {
                try {
                    MethodInfo methodInfo = AbstractRunner.getMethodInfo(config, classInfo, methodSignature);
                    if (Counter.filter(methodInfo)) {
                        methodInfos.add(methodInfo);
                    }
                } catch (IOException e) {
                    // Parsed method loading failures are reported by the main runner as well.
                }
            }
            return methodInfos;
        }
    }
}
