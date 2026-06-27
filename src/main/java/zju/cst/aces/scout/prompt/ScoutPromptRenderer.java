package zju.cst.aces.scout.prompt;

import freemarker.template.Configuration;
import freemarker.template.Template;

import java.io.StringWriter;
import java.nio.file.Path;

public class ScoutPromptRenderer {
    private final Path promptPath;

    public ScoutPromptRenderer(Path promptPath) {
        this.promptPath = promptPath;
    }

    public String renderUser(ScoutPromptContext context) {
        if (context == null) {
            throw new RuntimeException("Failed to render SCOUT prompt: context is null");
        }
        if (context.getMode() == null) {
            throw new RuntimeException("Failed to render SCOUT prompt: mode is null");
        }
        return render(context.getMode().getTemplate(), context);
    }

    public String renderSystem(String templateName, ScoutPromptContext context) {
        return render(templateName, context);
    }

    private String render(String templateName, ScoutPromptContext context) {
        try {
            Configuration configuration = new Configuration(Configuration.VERSION_2_3_30);
            if (promptPath == null) {
                configuration.setClassForTemplateLoading(ScoutPromptRenderer.class, "/prompt");
            } else {
                configuration.setDirectoryForTemplateLoading(promptPath.toFile());
            }
            configuration.setDefaultEncoding("utf-8");
            Template template = configuration.getTemplate(templateName);
            StringWriter writer = new StringWriter();
            template.process(context.getDataModel(), writer);
            return writer.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to render SCOUT prompt: " + templateName, e);
        }
    }
}
