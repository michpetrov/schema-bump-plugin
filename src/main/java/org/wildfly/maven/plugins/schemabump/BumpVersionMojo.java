package org.wildfly.maven.plugins.schemabump;


import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.components.interactivity.Prompter;
import org.codehaus.plexus.components.interactivity.PrompterException;

import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates new and updated existing files with a new subsystem version
 */
@Mojo(name = "bump-version", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class BumpVersionMojo extends AbstractMojo {

    private static final String EXTENSION_PATH = "/src/main/java/org/wildfly/extension";
    private static final String RESOURCES_PATH = "/src/main/resources";
    private static final String SCHEMA_PATH = RESOURCES_PATH + "/schema";
    private static final String TEST_RESOURCES_PATH = "/src/test/resources";

    private static final Pattern SCHEMA_PATTERN = Pattern.compile("wildfly-(.*)_(\\d\\d?_\\d)\\.xsd");

    @Parameter(defaultValue = "${project.build.directory}", property = "outputDir", readonly = true)
    private File outputDirectory;

    @Parameter(defaultValue = "${project.basedir}", property = "baseDir", required = true)
    private File baseDirectory;

    @Parameter(defaultValue = "true", property = "interactive")
    private boolean interactive;

    @Inject
    private Prompter prompter;

    public void execute() throws MojoExecutionException {
        File schemaFolder = new File(baseDirectory.getAbsolutePath() + SCHEMA_PATH);

        String[] schemaFiles = schemaFolder.list();
        if (schemaFiles == null) {
            throw new MojoExecutionException("No schema files found in " + schemaFolder.getAbsolutePath());
        }

        Matcher versionMatcher = findLatestSchemaFile(schemaFiles, "");

        String subsystem = versionMatcher.group(1);
        float oldV = Float.parseFloat(versionMatcher.group(2).replace('_', '.'));
        float newV = oldV + 1;

        if (interactive) {
            try {
                subsystem = prompter.prompt("Subsystem", subsystem);
                if (!subsystem.equals(versionMatcher.group(1))) {
                    versionMatcher = findLatestSchemaFile(schemaFiles, subsystem);

                    oldV = Float.parseFloat(versionMatcher.group(2).replace('_', '.'));
                    newV = oldV + 1;
                }
                newV = Float.parseFloat(prompter.prompt("New version (old " + oldV + ")", String.valueOf(newV)));
            } catch (PrompterException e) {
                throw new MojoExecutionException("Prompter error", e);
            }
        }

        getLog().debug("Identified subsystem: " + subsystem + ", new version: " + newV);

        Context ctx = new Context(subsystem, oldV, newV);

        createNewSchema(ctx, schemaFolder.getAbsolutePath() + "/" + versionMatcher.group());

        String subsystemFolderPath = getSubsystemFolderPath(subsystem);
        String newParser = createNewParser(ctx, subsystemFolderPath);

        modifyExtension(ctx, subsystemFolderPath);
        modifyNamespace(ctx, subsystemFolderPath);

        // TODO: test files/resources
    }

    private String getSubsystemFolderPath(String subsystem) throws MojoExecutionException {
        String extensionFolderPath = baseDirectory.getAbsolutePath() + EXTENSION_PATH;
        File subsystemFolder = new File( extensionFolderPath + "/" + subsystem.replace('-', '/'));
        if (!subsystemFolder.exists()) {
            subsystemFolder = new File(extensionFolderPath + "/" + subsystem.replace("-", ""));
            if (!subsystemFolder.exists()) {
                try {
                    if (interactive) {
                        getLog().info("Couldn't find subsystem folder for \"" + subsystem + "\"");
                        String folderPath = prompter.prompt("Extension folder path (relative to current folder)");
                        subsystemFolder = new File(baseDirectory.getAbsolutePath() + "/" + folderPath);
                    }
                    if (!subsystemFolder.exists()) {
                        throw new MojoExecutionException("Folder \"" + subsystemFolder.getAbsolutePath() + "\" does not exist");
                    }
                } catch (PrompterException e) {
                    throw new MojoExecutionException("Prompter error", e);
                }
            }
        }
        return subsystemFolder.getAbsolutePath();
    }

    private Matcher findLatestSchemaFile(String[] filenames, String subsystem) throws MojoExecutionException {
        String latestSchemaFile = Arrays.stream(filenames).filter(name -> subsystem.isEmpty() || name.contains(subsystem)).max(Comparator.naturalOrder()).get();

        Matcher versionMatcher = SCHEMA_PATTERN.matcher(latestSchemaFile);
        if (!versionMatcher.matches()) {
            throw new MojoExecutionException("Could not determine version from schema file: " + latestSchemaFile);
        }

        return versionMatcher;
    }

    private void modifyExtension(Context ctx, String sourceFolderPath) throws MojoExecutionException {
        File extension = getMatchingFile(sourceFolderPath, ".*Extension.java", false);
        String extensionPath = extension.getAbsolutePath();

        // ignore consequent matches
        var localContext = new Object() {
            boolean addedNewVersion = false;
            boolean changedCurrentVersion = false;
            boolean changedCurrentParser = false;
        };
        copyFromFile(extensionPath, extensionPath, line -> {
            String newLine = line.replaceAll(ctx.getOldVersionRegex(), ctx.getNewVersionRegex());
            if (!localContext.addedNewVersion && line.contains("static final ModelVersion") && line.matches(".*" + ctx.getOldVersionRegex() + ".*")) {
                localContext.addedNewVersion = true;
                return new Writable(newLine, line);
            } else if (!localContext.changedCurrentVersion && line.contains("ModelVersion") && line.contains("CURRENT")) {
                localContext.changedCurrentVersion = true;
                return new Writable(newLine);
            } else if (!localContext.changedCurrentParser && line.contains("CURRENT") && line.contains("Parser")) {
                Pattern currentParserPattern = Pattern.compile(" [^ ]*Parser_\\d\\d?_\\d (.*CURRENT.*) = ");
                Matcher m = currentParserPattern.matcher(line);
                m.find();
                ctx.setCurrentParserVariable(m.group(1));
                localContext.changedCurrentParser = true;
                return new Writable(newLine);
            } else if (line.contains("context.setSubsystemXmlMapping") && line.matches(".*" + ctx.getOldVersionRegex() + ".*")) {
                if (ctx.getCurrentParserVariable() != null && line.contains(ctx.getCurrentParserVariable())) {
                    String oldParserLine = line.replaceFirst(ctx.getCurrentParserVariable(), ctx.getOldParserClassName() + "::new");
                    return new Writable(oldParserLine, newLine);
                    // TODO: should this occur? oldparser = null?
                } else if (ctx.getOldParserClassName() != null && line.contains(ctx.getOldParserClassName())) {
                    return new Writable(line, newLine);
                }
            }
            return new Writable(line);
        });
    }

    private void modifyNamespace(Context ctx, String sourceFolderPath) throws MojoExecutionException {
        File namespace = getMatchingFile(sourceFolderPath, "Namespace.java", true);

        // namespaces might be defined in parsers but if they're not we're out of luck
        if (namespace == null) {
            getLog().debug("Namespace.java not found, skipping");
            return;
        }
        String namespacePath = namespace.getAbsolutePath();

        copyFromFile(namespacePath, namespacePath, line -> {
            String newLine = line.replaceAll(ctx.getOldVersionRegex(), ctx.getNewVersionRegex());
            if (line.matches(".*" + ctx.getOldVersionRegex() + ".*") && !line.contains("CURRENT")) {
                return new Writable(line, newLine);
            } else if (line.contains("CURRENT")) {
                return new Writable(newLine);
            }
            return new Writable(line);
        });
    }

    private String createNewSchema(Context ctx, String oldFilePath) throws MojoExecutionException {
        getLog().debug("Creating new schema from: " + oldFilePath);

        return copyFromFile(oldFilePath, oldFilePath.replaceFirst(ctx.getOldVersionRegex(), ctx.getNewVersionRegex()), line -> {
            if (line.contains("xmlns=\"urn:jboss:domain") ||
                    line.contains("targetNamespace=\"urn:jboss:domain") ||
                    line.contains("  version=\"")) {
                return new Writable(line.replaceFirst(ctx.getOldVersionRegex(), ctx.getNewVersionRegex()));
            }
            return new Writable(line);
        });
    }

    private String createNewParser(Context ctx, String folder) throws MojoExecutionException {
        File lastParser = getMatchingFile(folder, ".*Parser_" + ctx.getOldVersionRegex() + ".java", true);
        if (lastParser == null) {
            getLog().debug("No parser found, skipping");
            return "";
        }
        String fileName = lastParser.getName();
        getLog().debug("Creating new parser from: " + fileName);

        String lastParserClassName = fileName.substring(0, fileName.indexOf("."));
        ctx.setOldParserClassName(lastParserClassName);

        String lastParserPath = lastParser.getAbsolutePath();

        return copyFromFile(lastParserPath, lastParserPath.replaceFirst(ctx.getOldVersionRegex(), ctx.getNewVersionRegex()), line -> {
            // namespace OR classname
            if (line.contains("\"urn:jboss:domain") || line.contains(lastParserClassName)) {
                return new Writable(line.replaceFirst(ctx.getOldVersionRegex(), ctx.getNewVersionRegex()));
            }
            return new Writable(line);
        });
    }

    private String copyFromFile(String oldFilePath, String newFilePath, Function<String, Writable> processLine) throws MojoExecutionException {
        String newFileName = newFilePath.substring(newFilePath.lastIndexOf(File.separator) + 1);

        getLog().debug("Writing new file: " + newFilePath + ", from: " + oldFilePath);

        String tmpPath = null;
        boolean overwrite = oldFilePath.equals(newFilePath);
        if (overwrite) {
            tmpPath = newFilePath;
            outputDirectory.mkdirs();
            newFilePath = outputDirectory.getAbsolutePath() + "/" + newFileName;
        }
        try {
            boolean notExists = new File(newFilePath).createNewFile();
            if (!(notExists || overwrite)) {
                throw new MojoExecutionException("File " + newFilePath + " already exists");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Error creating file: " + newFileName, e);
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(oldFilePath));
             BufferedWriter writer = new BufferedWriter(new FileWriter(newFilePath))) {

            String line;
            while ((line = reader.readLine()) != null) {
                Writable result = processLine.apply(line);
                result.write(writer);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (overwrite) {
            boolean renamed = new File(newFilePath).renameTo(new File(tmpPath));
            if (!renamed) {
                throw new MojoExecutionException("Could not modify file " + newFilePath);
            }
        }
        getLog().info((overwrite ? "modified: " : "created: ") + newFileName);
        return newFileName;
    }

    private File getMatchingFile(String folderPath, String match, boolean optional) throws MojoExecutionException {
        File[] matchedFiles = new File(folderPath).listFiles((file, s) -> s.matches(match));

        String errorAmount = null;
        if (matchedFiles == null) {
            errorAmount = "no";
        } else if (matchedFiles.length == 0) {
            if (optional) {
                return null;
            }
            errorAmount = "no";
        } else if (matchedFiles.length > 1) {
            errorAmount = "more than one";
        }

        if (errorAmount != null) {
            throw new MojoExecutionException("Found " + errorAmount + " file matching \"" + match + "\" in " + folderPath);
        }

        return matchedFiles[0];
    }

    static class Context {
        private final String subsystem;
        private final String oldVersionRegex;
        private final String newVersionRegex;

        private String oldParserClassName;
        private String currentParserVariable;

        public Context(String subsystem, float oldVersion, float newVersion) {
            this.subsystem = subsystem;
            /*
                String.replaceAll("3([._]|, )0", "4$10") preserves the separator
                3.0 => 4.0, 3_0 => 4_0, "3, 0" => "4, 0"
            */
            oldVersionRegex = String.valueOf(oldVersion).replace(".", "([._]|, )");
            newVersionRegex = String.valueOf(newVersion).replace(".", "$1");
        }

        public void setOldParserClassName(String oldParserClassName) {
            this.oldParserClassName = oldParserClassName;
        }

        public void setCurrentParserVariable(String currentParserVariable) {
            this.currentParserVariable = currentParserVariable;
        }

        public String getSubsystem() {
            return subsystem;
        }

        public String getOldVersionRegex() {
            return oldVersionRegex;
        }

        public String getNewVersionRegex() {
            return newVersionRegex;
        }

        public String getOldParserClassName() {
            return oldParserClassName;
        }

        public String getCurrentParserVariable() {
            return currentParserVariable;
        }
    }

    static class Writable {

        private final String[] lines;

        public Writable(String... lines) {
            this.lines = lines;
        }

        public void write(BufferedWriter writer) throws IOException {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        }
    }
}
