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

    private enum WriteOption {
        COPY, INSERT, REPLACE
    }

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
                newV = Float.parseFloat(prompter.prompt("New version", String.valueOf(newV)));
            } catch (PrompterException e) {
                throw new MojoExecutionException("Prompter error", e);
            }
        }

        getLog().debug("Identified subsystem: " + subsystem + ", new version: " + newV);

        /*
          String.replaceAll("3([._]|, )0", "4$10") preserves the separator
          3.0 => 4.0, 3_0 => 4_0, "3, 0" => "4, 0"
         */
        String oldVersionRegex = String.valueOf(oldV).replace(".", "([._]|, )");
        String newVersionRegex = String.valueOf(newV).replace(".", "$1");

        createNewSchema(oldVersionRegex, newVersionRegex, schemaFolder.getAbsolutePath() + "/" + versionMatcher.group());

        String subsystemFolderPath = baseDirectory.getAbsolutePath() + "/" + EXTENSION_PATH + "/" + subsystem.replace('-', '/');

        String newParser = createNewParser(oldVersionRegex, newVersionRegex, subsystemFolderPath);

        modifyExtension(oldVersionRegex, newVersionRegex, subsystemFolderPath, newParser);
    }

    private Matcher findLatestSchemaFile(String[] filenames, String subsystem) throws MojoExecutionException {
        String latestSchemaFile = Arrays.stream(filenames).filter(name -> subsystem.isEmpty() || name.contains(subsystem)).max(Comparator.naturalOrder()).get();

        Matcher versionMatcher = SCHEMA_PATTERN.matcher(latestSchemaFile);
        if (!versionMatcher.matches()) {
            throw new MojoExecutionException("Could not determine version from schema file: " + latestSchemaFile);
        }

        return versionMatcher;
    }

    private void modifyExtension(String oldVersion, String newVersion, String sourceFolderPath, String newParser) throws MojoExecutionException {
        File extension = getMatchingFile(sourceFolderPath, ".*Extension.java");
        String extensionPath = extension.getAbsolutePath();

        // ignore consequent matches
        var context = new Object() {
            boolean addedNewVersion = false;
            boolean changedCurrentVersion = false;
            boolean changedCurrentParser = false;

            String oldParserClassName;
            String currentParserVariable;
        };
        copyFromFile(extensionPath, extensionPath, line -> {
            String newLine = line.replaceAll(oldVersion, newVersion);
            if (!context.addedNewVersion && line.contains("static final ModelVersion") && line.matches(".*" + oldVersion + ".*")) {
                context.addedNewVersion = true;
                return new Writable(newLine, line);
            } else if (!context.changedCurrentVersion && line.contains("ModelVersion") && line.contains("CURRENT")) {
                context.changedCurrentVersion = true;
                return new Writable(newLine);
            } else if (!context.changedCurrentParser && line.contains("CURRENT") && line.contains("Parser")) {
                Pattern currentParserPattern = Pattern.compile(" ([^ ]*Parser_\\d\\d?_\\d) (.*CURRENT.*) = ");
                Matcher m = currentParserPattern.matcher(line);
                m.find();
                context.oldParserClassName = m.group(1);
                context.currentParserVariable = m.group(2);
                context.changedCurrentParser = true;
                return new Writable(newLine);
            } else if (context.currentParserVariable != null && line.contains(context.oldParserClassName) && line.contains(context.currentParserVariable)) {
                String oldParserLine = line.replaceFirst(context.currentParserVariable, context.oldParserClassName + "::new");
                return new Writable(oldParserLine, newLine);
            }
            return new Writable(line);
        });
    }

    private String createNewSchema(String oldVersion, String newVersion, String oldFilePath) throws MojoExecutionException {
        getLog().debug("Creating new schema from: " + oldFilePath);

        return copyFromFile(oldFilePath, oldFilePath.replaceFirst(oldVersion, newVersion), line -> {
            if (line.contains("xmlns=\"urn:jboss:domain") ||
                    line.contains("targetNamespace=\"urn:jboss:domain") ||
                    line.contains("  version=\"")) {
                return new Writable(line.replaceFirst(oldVersion, newVersion));
            }
            return new Writable(line);
        });
    }

    private String createNewParser(String oldVersion, String newVersion, String folder) throws MojoExecutionException {
        File lastParser = getMatchingFile(folder, ".*Parser_" + oldVersion + ".java");
        String fileName = lastParser.getName();
        getLog().debug("Creating new parser from: " + fileName);

        String lastParserPath = lastParser.getAbsolutePath();

        return copyFromFile(lastParserPath, lastParserPath.replaceFirst(oldVersion, newVersion), line -> {
            // namespace OR classname
            if (line.contains("\"urn:jboss:domain") || line.contains(fileName.substring(0, fileName.indexOf(".")))) {
                return new Writable(line.replaceFirst(oldVersion, newVersion));
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

    private File getMatchingFile(String folderPath, String match) throws MojoExecutionException {
        File[] matchedFiles = new File(folderPath).listFiles((file, s) -> s.matches(match));

        String errorAmount = null;
        if (matchedFiles == null || matchedFiles.length == 0) {
            errorAmount = "no";
        } else if (matchedFiles.length > 1) {
            errorAmount = "more than one";
        }

        if (errorAmount != null) {
            throw new MojoExecutionException("Found " + errorAmount + " file matching \"" + match + "\" in " + folderPath);
        }

        return matchedFiles[0];
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
