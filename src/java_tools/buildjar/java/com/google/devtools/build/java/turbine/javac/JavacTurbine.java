// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.java.turbine.javac;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.buildjar.JarOwner;
import com.google.devtools.build.buildjar.javac.JavacOptions;
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule;
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.StrictJavaDeps;
import com.google.devtools.build.buildjar.javac.plugins.dependency.StrictJavaDepsPlugin;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.options.TurbineOptions;
import com.google.turbine.options.TurbineOptionsParser;
import com.sun.tools.javac.util.Context;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * An header compiler implementation based on javac.
 *
 * <p>This is a reference implementation used to develop the blaze integration, and to validate the
 * real header compilation implementation.
 */
public class JavacTurbine implements AutoCloseable {

  private static final Splitter SPACE_SPLITTER = Splitter.on(' ');

  public static void main(String[] args) throws IOException {
    System.exit(compile(TurbineOptionsParser.parse(Arrays.asList(args))).exitCode());
  }

  public static Result compile(TurbineOptions turbineOptions) throws IOException {
    try (JavacTurbine turbine =
        new JavacTurbine(
            new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err, UTF_8))),
            turbineOptions)) {
      return turbine.compile();
    }
  }

  /** A header compilation result. */
  public enum Result {
    /** The compilation succeeded with the reduced classpath optimization. */
    OK_WITH_REDUCED_CLASSPATH(true),

    /** The compilation succeeded, but had to fall back to a transitive classpath. */
    OK_WITH_FULL_CLASSPATH(true),

    /** The compilation did not succeed. */
    ERROR(false);

    private final boolean ok;

    private Result(boolean ok) {
      this.ok = ok;
    }

    public boolean ok() {
      return ok;
    }

    public int exitCode() {
      return ok ? 0 : 1;
    }
  }

  private static final int ZIPFILE_BUFFER_SIZE = 1024 * 16;


  private final PrintWriter out;
  private final TurbineOptions turbineOptions;
  @VisibleForTesting Context context;

  /** Cache of opened zip filesystems for srcjars. */
  private final Map<Path, FileSystem> filesystems = new HashMap<>();

  public JavacTurbine(PrintWriter out, TurbineOptions turbineOptions) {
    this.out = out;
    this.turbineOptions = turbineOptions;
  }

  Result compile() throws IOException {
    ImmutableList.Builder<String> argbuilder = ImmutableList.builder();

    argbuilder.addAll(JavacOptions.removeBazelSpecificFlags(turbineOptions.javacOpts()));

    // Disable compilation of implicit source files.
    // This is insurance: the sourcepath is empty, so we don't expect implicit sources.
    argbuilder.add("-implicit:none");

    // Disable debug info
    argbuilder.add("-g:none");

    // Enable MethodParameters
    argbuilder.add("-parameters");

    // Compile-time jars always use Java 8
    argbuilder.add("-source");
    argbuilder.add("8");
    argbuilder.add("-target");
    argbuilder.add("8");

    ImmutableList<Path> processorpath;
    if (!turbineOptions.processors().isEmpty()) {
      argbuilder.add("-processor");
      argbuilder.add(Joiner.on(',').join(turbineOptions.processors()));
      processorpath = asPaths(turbineOptions.processorPath());
    } else {
      processorpath = ImmutableList.of();
    }

    ImmutableList<Path> sources =
        ImmutableList.<Path>builder()
            .addAll(asPaths(turbineOptions.sources()))
            .addAll(getSourceJarEntries(turbineOptions))
            .build();

    JavacTurbineCompileRequest.Builder requestBuilder =
        JavacTurbineCompileRequest.builder()
            .setSources(sources)
            .setJavacOptions(argbuilder.build())
            .setBootClassPath(asPaths(turbineOptions.bootClassPath()))
            .setProcessorClassPath(processorpath);

    // JavaBuilder exempts some annotation processors from Strict Java Deps enforcement.
    // To avoid having to apply the same exemptions here, we just ignore strict deps errors
    // and leave enforcement to JavaBuilder.
    ImmutableSet<Path> platformJars = ImmutableSet.copyOf(asPaths(turbineOptions.bootClassPath()));
    DependencyModule dependencyModule =
        buildDependencyModule(turbineOptions, StrictJavaDeps.WARN, platformJars);

    if (sources.isEmpty()) {
      // accept compilations with an empty source list for compatibility with JavaBuilder
      emitClassJar(
          Paths.get(turbineOptions.outputFile()),
          /* files= */ ImmutableMap.of(),
          /* transitive= */ ImmutableMap.of());
      dependencyModule.emitDependencyInformation(
          /*classpath=*/ ImmutableList.of(), /*successful=*/ true);
      return Result.OK_WITH_REDUCED_CLASSPATH;
    }

    Result result = Result.ERROR;
    JavacTurbineCompileResult compileResult = null;
    ImmutableList<Path> actualClasspath = ImmutableList.of();

    ImmutableList<Path> originalClasspath = asPaths(turbineOptions.classPath());
    ImmutableList<Path> compressedClasspath =
        dependencyModule.computeStrictClasspath(originalClasspath);

    requestBuilder.setStrictDepsPlugin(new StrictJavaDepsPlugin(dependencyModule));

    JavacTransitive transitive = new JavacTransitive(platformJars);
    requestBuilder.setTransitivePlugin(transitive);

    if (turbineOptions.shouldReduceClassPath()) {
      // compile with reduced classpath
      actualClasspath = compressedClasspath;
      requestBuilder.setClassPath(actualClasspath);
      compileResult = JavacTurbineCompiler.compile(requestBuilder.build());
      if (compileResult.success()) {
        result = Result.OK_WITH_REDUCED_CLASSPATH;
        context = compileResult.context();
      }
    }

    if (compileResult == null
        || (!compileResult.success() && hasRecognizedError(compileResult.output()))) {
      // fall back to transitive classpath
      actualClasspath = originalClasspath;
      requestBuilder.setClassPath(actualClasspath);
      compileResult = JavacTurbineCompiler.compile(requestBuilder.build());
      if (compileResult.success()) {
        result = Result.OK_WITH_FULL_CLASSPATH;
        context = compileResult.context();
      }
    }

    if (result.ok()) {
      emitClassJar(
          Paths.get(turbineOptions.outputFile()),
          compileResult.files(),
          transitive.collectTransitiveDependencies());
      dependencyModule.emitDependencyInformation(actualClasspath, compileResult.success());
    } else {
      out.print(compileResult.output());
    }
    return result;
  }

  private static DependencyModule buildDependencyModule(
      TurbineOptions turbineOptions,
      StrictJavaDeps strictDepsMode,
      ImmutableSet<Path> platformJars) {
    DependencyModule.Builder dependencyModuleBuilder =
        new DependencyModule.Builder()
            .setReduceClasspath()
            .setTargetLabel(turbineOptions.targetLabel().orNull())
            .addDepsArtifacts(asPaths(turbineOptions.depsArtifacts()))
            .setPlatformJars(platformJars)
            .setStrictJavaDeps(strictDepsMode.toString())
            .addDirectMappings(parseJarsToTargets(turbineOptions.directJarsToTargets()))
            .addIndirectMappings(parseJarsToTargets(turbineOptions.indirectJarsToTargets()));

    if (turbineOptions.outputDeps().isPresent()) {
      dependencyModuleBuilder.setOutputDepsProtoFile(Paths.get(turbineOptions.outputDeps().get()));
    }

    return dependencyModuleBuilder.build();
  }

  private static ImmutableMap<Path, JarOwner> parseJarsToTargets(
      ImmutableMap<String, String> input) {
    ImmutableMap.Builder<Path, JarOwner> result = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : input.entrySet()) {
      result.put(Paths.get(entry.getKey()), parseJarOwner(entry.getKey()));
    }
    return result.build();
  }

  private static JarOwner parseJarOwner(String line) {
    List<String> ownerStringParts = SPACE_SPLITTER.splitToList(line);
    JarOwner owner;
    Preconditions.checkState(ownerStringParts.size() == 1 || ownerStringParts.size() == 2);
    if (ownerStringParts.size() == 1) {
      owner = JarOwner.create(ownerStringParts.get(0));
    } else {
      owner = JarOwner.create(ownerStringParts.get(0), ownerStringParts.get(1));
    }
    return owner;
  }

  /** Write the class output from a successful compilation to the output jar. */
  private static void emitClassJar(
      Path outputJar, Map<String, byte[]> files, Map<String, byte[]> transitive)
      throws IOException {
    try (OutputStream fos = Files.newOutputStream(outputJar);
        ZipOutputStream zipOut =
            new ZipOutputStream(new BufferedOutputStream(fos, ZIPFILE_BUFFER_SIZE))) {
      for (Map.Entry<String, byte[]> entry : transitive.entrySet()) {
        String name = entry.getKey();
        byte[] bytes = entry.getValue();
        ZipUtil.storeEntry(ClassPathBinder.TRANSITIVE_PREFIX + name + ".class", bytes, zipOut);
      }
      for (Map.Entry<String, byte[]> entry : files.entrySet()) {
        String name = entry.getKey();
        byte[] bytes = entry.getValue();
        if (bytes == null) {
          continue;
        }
        if (name.endsWith(".class")) {
          bytes = processBytecode(bytes);
        }
        ZipUtil.storeEntry(name, bytes, zipOut);
      }
    }
  }

  /**
   * Remove code attributes and private members.
   *
   * <p>Most code will already have been removed after parsing, but the bytecode will still contain
   * e.g. lowered class and instance initializers.
   */
  private static byte[] processBytecode(byte[] bytes) {
    ClassWriter cw = new ClassWriter(0);
    new ClassReader(bytes)
        .accept(
            new PrivateMemberPruner(cw),
            ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    return cw.toByteArray();
  }

  /**
   * Prune bytecode.
   *
   * <p>Like ijar, turbine prunes private fields and members to improve caching and reduce output
   * size.
   *
   * <p>This is not always a safe optimization: it can prevent javac from emitting diagnostics e.g.
   * when a public member is hidden by a private member which has then pruned. The impact of that is
   * believed to be small, and as long as ijar continues to prune private members turbine should do
   * the same for compatibility.
   *
   * <p>Some of this work could be done during tree pruning, but it's not completely trivial to
   * detect private members at that point (e.g. with implicit modifiers).
   */
  static class PrivateMemberPruner extends ClassVisitor {
    public PrivateMemberPruner(ClassVisitor cv) {
      super(Opcodes.ASM5, cv);
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String desc, String signature, Object value) {
      if ((access & Opcodes.ACC_PRIVATE) == Opcodes.ACC_PRIVATE) {
        return null;
      }
      return super.visitField(access, name, desc, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      if ((access & Opcodes.ACC_PRIVATE) == Opcodes.ACC_PRIVATE) {
        return null;
      }
      if (name.equals("<clinit>")) {
        // drop class initializers, which are going to be empty after tree pruning
        return null;
      }
      // drop synthetic methods, including bridges (see b/31653210)
      if ((access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
        return null;
      }
      return super.visitMethod(access, name, desc, signature, exceptions);
    }
  }

  /** Convert string elements of a classpath to {@link Path}s. */
  private static ImmutableList<Path> asPaths(Iterable<String> classpath) {
    ImmutableList.Builder<Path> result = ImmutableList.builder();
    for (String element : classpath) {
      result.add(Paths.get(element));
    }
    return result.build();
  }

  /** Returns paths to the source jar entries to compile. */
  private ImmutableList<Path> getSourceJarEntries(TurbineOptions turbineOptions)
      throws IOException {
    ImmutableList.Builder<Path> sources = ImmutableList.builder();
    for (String sourceJar : turbineOptions.sourceJars()) {
      for (Path root : getJarFileSystem(Paths.get(sourceJar)).getRootDirectories()) {
        Files.walkFileTree(
            root,
            new SimpleFileVisitor<Path>() {
              @Override
              public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                  throws IOException {
                String fileName = path.getFileName().toString();
                if (fileName.endsWith(".java")) {
                  sources.add(path);
                }
                return FileVisitResult.CONTINUE;
              }
            });
      }
    }
    return sources.build();
  }

  private FileSystem getJarFileSystem(Path sourceJar) throws IOException {
    FileSystem fs = filesystems.get(sourceJar);
    if (fs == null) {
      filesystems.put(sourceJar, fs = FileSystems.newFileSystem(sourceJar, null));
    }
    return fs;
  }

  private static final Pattern MISSING_PACKAGE =
      Pattern.compile("error: package ([\\p{javaJavaIdentifierPart}\\.]+) does not exist");

  /**
   * The compilation failed with an error that may indicate that the reduced class path was too
   * aggressive.
   *
   * <p>WARNING: keep in sync with ReducedClasspathJavaLibraryBuilder.
   */
  // TODO(cushon): use a diagnostic listener and match known codes instead
  private static boolean hasRecognizedError(String javacOutput) {
    return javacOutput.contains("error: cannot access")
        || javacOutput.contains("error: cannot find symbol")
        || javacOutput.contains("com.sun.tools.javac.code.Symbol$CompletionFailure")
        || MISSING_PACKAGE.matcher(javacOutput).find();
  }

  @Override
  public void close() throws IOException {
    out.flush();
    for (FileSystem fs : filesystems.values()) {
      fs.close();
    }
  }
}
