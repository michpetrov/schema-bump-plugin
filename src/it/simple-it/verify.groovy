import java.nio.file.Files
import java.nio.file.Paths

File newParser = new File( basedir, '/src/main/java/org/wildfly/extension/example/ExampleSubsystemParser_18_0.java');
assert newParser.isFile()
assert Files.lines(Paths.get(newParser.getAbsolutePath())).noneMatch { line -> line.contains("17")}

File newSchema = new File( basedir, '/src/main/resources/schema/wildfly-example_18_0.xsd' );
assert newSchema.isFile()
assert Files.lines(Paths.get(newSchema.getAbsolutePath())).noneMatch {line -> line.contains("17")}

//File newParser = new File( baseDirectory, "/src/main/java/org/wildfly/extension/ExampleSubsystemParser_18_0.java" );
//assert touchFile.isFile()