import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import java.io.File;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;

/** Modular project model and maker. */
class Make implements ToolProvider {

  /** Version is either {@code master} or {@link Runtime.Version#parse(String)}-compatible. */
  static final String VERSION = "master";

  /** Convenient short-cut to {@code "user.dir"} as a path. */
  static final Path USER_PATH = Path.of(System.getProperty("user.dir"));

  /** Main entry-point. */
  public static void main(String... args) {
    var code = Make.of(USER_PATH).run(System.out, System.err, args);
    if (code != 0) {
      throw new Error("Make.java failed with error code: " + code);
    }
  }

  /** Create instance using the given path as the home of project. */
  static Make of(Path home) {
    var debug = Boolean.getBoolean("ebug");
    var dryRun = Boolean.getBoolean("ry-run");
    var project = System.getProperty("project.name", home.getFileName().toString());
    var version = System.getProperty("project.version", "1.0.0-SNAPSHOT");
    var work = home.resolve("work");
    var main = Realm.of("main", home, work);
    var realms = new ArrayList<Realm>();
    realms.add(main);
    try {
      realms.add(Realm.of("test", home, work, main));
    } catch (Error e) {
      // ignore missing test realm...
    }
    return new Make(debug, dryRun, project, version, home, realms);
  }

  /** Debug flag. */
  final boolean debug;
  /** Dry-run flag. */
  final boolean dryRun;
  /** Name of the project. */
  final String project;
  /** Version of the project. */
  final String version;
  /** Root path of this project. */
  final Path home;
  /** Realms of this project. */
  final List<Realm> realms;

  Make(
      boolean debug,
      boolean dryRun,
      String project,
      String version,
      Path home,
      List<Realm> realms) {
    this.debug = debug;
    this.dryRun = dryRun;
    this.project = project;
    this.version = version;
    this.home = home.normalize().toAbsolutePath();
    this.realms = realms;
  }

  @Override
  public String name() {
    return "Make.java";
  }

  @Override
  public int run(PrintWriter out, PrintWriter err, String... args) {
    var run = new Run(debug ? System.Logger.Level.ALL : System.Logger.Level.INFO, out, err);
    return run(run, args);
  }

  int run(Run run, String... args) {
    run.log(DEBUG, "%s - %s", name(), VERSION);
    run.log(DEBUG, "  args = %s", List.of(args));
    run.log(DEBUG, "  java = %s", Runtime.version());
    run.log(DEBUG, "Building project '%s', version %s...", project, version);
    run.log(DEBUG, "  home = %s", home.toUri());
    for (int i = 0; i < realms.size(); i++) {
      run.log(DEBUG, "  realms[%d] = %s", i, realms.get(i));
    }
    if (dryRun) {
      run.log(INFO, "Dry-run ends here.");
      return 0;
    }
    try {
      build(run); // compile + jar
      junit(run); // test
      var main = realms.get(0);
      document(run, main); // javadoc + x
      summary(run, main);
      run.log(INFO, "Build successful after %d ms.", run.toDurationMillis());
      return 0;
    } catch (Throwable throwable) {
      run.log(ERROR, "Build failed: %s", throwable.getMessage());
      throwable.printStackTrace(run.err);
      return 1;
    }
  }

  /** Sequentially build all realms. */
  private void build(Run run) throws Exception {
    for (var realm : realms) {
      var moduleSourcePath = home.resolve(realm.source);
      if (Files.notExists(moduleSourcePath)) {
        run.log(WARNING, "Source path of %s realm not found: %s", realm.name, moduleSourcePath);
        return;
      }
      if (realm.modules.isEmpty()) {
        throw new Error("No modules found in source path: " + moduleSourcePath);
      }
      assemble(run, realm);
      build(run, realm);
    }
  }

  /** Assemble assets and check preconditions. */
  private void assemble(Run run, Realm realm) throws Exception {
    run.log(DEBUG, "Assembling assets for %s realm...", realm.name);
    var offline = Boolean.getBoolean("offline");
    var libraries = home.resolve(realm.libraries);
    var candidates =
        List.of(
            libraries.resolve(realm.name),
            libraries.resolve(realm.name + "-compile-only"),
            libraries.resolve(realm.name + "-runtime-only"));
    var downloaded = new ArrayList<Path>();
    for (var candidate : candidates) {
      if (!Files.isDirectory(candidate)) {
        continue;
      }
      for (var path : Util.listFiles(candidate, name -> name.equals("module-uri.properties"))) {
        var directory = path.getParent();
        var properties = new Properties();
        try (var stream = Files.newInputStream(path)) {
          properties.load(stream);
          run.log(DEBUG, "Resolving %d modules in %s", properties.size(), directory.toUri());
          for (var value : properties.values()) {
            var string = value.toString();
            var uri = URI.create(string);
            uri = uri.isAbsolute() ? uri : home.resolve(string).toUri();
            run.log(DEBUG, " o %s", uri);
            downloaded.add(Util.download(offline, directory, uri));
          }
        }
      }
    }
    run.log(DEBUG, "Downloaded %d modules.", downloaded.size());
    run.log(DEBUG, "Assembled assets for %s realm.", realm.name);
  }

  /** Build given realm. */
  private void build(Run run, Realm realm) {
    var pendingModules = new ArrayList<>(realm.modules);
    var builders = List.of(new MultiReleaseBuilder(run, realm), new DefaultBuilder(run, realm));
    for (var builder : builders) {
      var builtModules = builder.build(pendingModules);
      pendingModules.removeAll(builtModules);
      if (pendingModules.isEmpty()) {
        return;
      }
    }
    throw new IllegalStateException("Pending module list is not empty! " + pendingModules);
  }

  /** Launch JUnit Platform for all realms that signal to contain tests. */
  private void junit(Run run) throws Exception {
    for (var realm : realms) {
      if (realm.containsTests()) {
        junit(run, realm);
      }
    }
  }

  /** Launch JUnit Platform for given realm. */
  private void junit(Run run, Realm realm) throws Exception {
    var modulePath = new ArrayList<Path>();
    modulePath.add(realm.compiledModules); // grab test modules
    modulePath.addAll(realm.modulePaths.get("runtime"));
    var java =
        new Args()
            // .with("--show-version")
            .with("--module-path", modulePath)
            // .with("--show-module-resolution")
            .with("--illegal-access=debug")
            .with("-Dsun.reflect.debugModuleAccessChecks")
            .with(
                "--add-opens",
                "org.junit.jupiter.api/org.junit.jupiter.api.condition=org.junit.platform.commons")
            .with("--add-modules", String.join(",", realm.modules));
    var junit =
        new Args()
            .with("--fail-if-no-tests")
            .with("--reports-dir", realm.target.resolve("junit-reports"))
            .with("--scan-modules");
    // TODO Starting JUnit Platform Console in an external process for now...
    var program = ProcessHandle.current().info().command().map(Path::of).orElseThrow();
    var command = new Args().with(program.resolveSibling("java"));
    command.addAll(java);
    command.with("--module", "org.junit.platform.console").withEach(junit);
    run.log(INFO, "JUnit: %s", command);
    run.out.flush();
    var process = new ProcessBuilder(command.toStringArray()).inheritIO().start();
    var code = process.waitFor();
    if (code != 0) {
      throw new AssertionError("JUnit run exited with code " + code);
    }
  }

  /** Generate documentation for given realm. */
  private void document(Run run, Realm realm) throws Exception {
    // TODO javadoc: error - Destination directory not writable: ${work}/main/compiled/javadoc
    // var destination = Files.createDirectories(realm.compiledJavadoc);
    var moduleSourcePath = home.resolve(realm.source);
    var javaSources = new ArrayList<String>();
    javaSources.add(moduleSourcePath.toString());
    for (var release = 7; release <= Runtime.version().feature(); release++) {
      var separator = File.separator;
      javaSources.add(moduleSourcePath + separator + "*" + separator + "java-" + release);
    }
    var javadoc =
        new Args()
            .with(false, "-verbose")
            .with("-encoding", "UTF-8")
            .with("-quiet")
            .with("-windowtitle", project + " " + version)
            .with("-d", realm.compiledJavadoc)
            .with("--module-source-path", String.join(File.pathSeparator, javaSources))
            .with("--module", String.join(",", realm.modules));
    var modulePath = realm.modulePaths.get("compile");
    if (!modulePath.isEmpty()) {
      javadoc.with("--module-path", modulePath);
    }
    run.tool("javadoc", javadoc.toStringArray());
    Files.createDirectories(realm.packagedJavadoc);
    var javadocJar = realm.packagedJavadoc.resolve(project + '-' + version + "-javadoc.jar");
    var jar =
        new Args()
            .with(debug, "--verbose")
            .with("--create")
            .with("--file", javadocJar)
            .with("-C", realm.compiledJavadoc)
            .with(".");
    run.tool("jar", jar.toStringArray());
  }

  /** Log summary for given realm. */
  private void summary(Run run, Realm realm) {
    var jars = Util.listFiles(List.of(realm.packagedModules), Util::isJarFile);
    jars.forEach(jar -> run.log(INFO, "  -> " + jar.getFileName()));
    if (debug) {
      var modulePath = new ArrayList<Path>();
      modulePath.add(realm.packagedModules);
      modulePath.addAll(realm.modulePaths.get("runtime"));
      var jdeps =
          new Make.Args()
              .with("--module-path", modulePath)
              .with("--add-modules", String.join(",", realm.modules))
              .with("--multi-release", "base")
              .with("-summary");
      run.tool("jdeps", jdeps.toStringArray());
    }
  }

  /** Command-line program argument list builder. */
  static class Args extends ArrayList<String> {
    /** Add single argument by invoking {@link Object#toString()} on the given argument. */
    Args with(Object argument) {
      add(argument.toString());
      return this;
    }

    Args with(boolean condition, Object argument) {
      return condition ? with(argument) : this;
    }

    /** Add two arguments by invoking {@link #with(Object)} for the key and value elements. */
    Args with(Object key, Object value) {
      return with(key).with(value);
    }

    /** Add two arguments, i.e. the key and the paths joined by system's path separator. */
    Args with(Object key, List<Path> paths) {
      var value =
          paths.stream()
              // .filter(Files::isDirectory)
              .map(Object::toString)
              .collect(Collectors.joining(File.pathSeparator));
      return with(key, value);
    }

    /** Add all arguments by invoking {@link #with(Object)} for each element. */
    Args withEach(Iterable<?> arguments) {
      arguments.forEach(this::with);
      return this;
    }

    String[] toStringArray() {
      return toArray(String[]::new);
    }
  }

  /** Runtime context information. */
  static class Run {
    /** Current logging level threshold. */
    final System.Logger.Level threshold;
    /** Stream to which "expected" output should be written. */
    final PrintWriter out;
    /** Stream to which any error messages should be written. */
    final PrintWriter err;
    /** Time instant recorded on creation of this instance. */
    final Instant start;

    Run(System.Logger.Level threshold, PrintWriter out, PrintWriter err) {
      this.threshold = threshold;
      this.out = out;
      this.err = err;
      this.start = Instant.now();
    }

    /** Log message unless threshold suppresses it. */
    void log(System.Logger.Level level, String format, Object... args) {
      if (level.getSeverity() < threshold.getSeverity()) {
        return;
      }
      var consumer = level.getSeverity() < WARNING.getSeverity() ? out : err;
      var message = String.format(format, args);
      consumer.println(message);
    }

    /** Run provided tool. */
    void tool(String name, String... args) {
      log(DEBUG, "Running tool '%s' with: %s", name, List.of(args));
      var tool = ToolProvider.findFirst(name).orElseThrow();
      var code = tool.run(out, err, args);
      if (code == 0) {
        log(DEBUG, "Tool '%s' successfully executed.", name);
        return;
      }
      throw new Error("Tool '" + name + "' execution failed with error code: " + code);
    }

    long toDurationMillis() {
      return TimeUnit.MILLISECONDS.convert(Duration.between(start, Instant.now()));
    }
  }

  /** Building block, source set, scope, directory, named context: {@code main}, {@code test}. */
  static class Realm {
    /** Create realm by guessing the module source path using its name. */
    static Realm of(String name, Path home, Path target, Realm... requiredRealms) {
      var source =
          Util.findFirstDirectory(home, "src/" + name + "/java", "src/" + name, name)
              .map(home::relativize)
              .orElseThrow(() -> new Error("Couldn't find module source path!"));
      // TODO Find at least one "module-info.java" file...
      var modules = Util.listDirectoryNames(home.resolve(source));
      var modulePaths =
          Map.of(
              "compile", modulePath(name, home, "compile", requiredRealms),
              "runtime", modulePath(name, home, "runtime", requiredRealms));
      return new Realm(name, source, modules, target, modulePaths);
    }

    /** Create module path. */
    static List<Path> modulePath(String name, Path home, String phase, Realm... requiredRealms) {
      var result = new ArrayList<Path>();
      var candidates = List.of(name, name + "-" + phase + "-only");
      for (var candidate : candidates) {
        var lib = home.resolve("lib").resolve(candidate);
        if (Files.isDirectory(lib)) {
          result.add(lib);
        }
      }
      for (var required : requiredRealms) {
        result.add(required.packagedModules);
        result.addAll(required.modulePaths.get(phase));
      }
      return result;
    }

    /** Logical name of the realm. */
    final String name;
    /** Module source path. */
    final Path source;
    /** Path to external 3rd-party libraries. */
    final Path libraries;
    /** Names of all modules declared in this realm. */
    final List<String> modules;
    /** Target root. */
    final Path target;
    /** Module path map. */
    final Map<String, List<Path>> modulePaths;

    final Path compiledBase;
    final Path compiledJavadoc;
    final Path compiledModules;
    final Path compiledMulti;
    final Path packagedJavadoc;
    final Path packagedModules;
    final Path packagedSources;

    Realm(
        String name,
        Path source,
        List<String> modules,
        Path target,
        Map<String, List<Path>> modulePaths) {
      this.name = name;
      this.source = source;
      this.modules = modules;
      this.target = target;
      this.modulePaths = modulePaths;

      this.libraries = Path.of("lib");

      var work = target.resolve(name);
      compiledBase = work.resolve("compiled");
      compiledJavadoc = compiledBase.resolve("javadoc");
      compiledModules = compiledBase.resolve("modules");
      compiledMulti = compiledBase.resolve("multi-release");
      packagedJavadoc = work.resolve("javadoc");
      packagedModules = work.resolve("modules");
      packagedSources = work.resolve("sources");
    }

    /** Realm does not need to be treated with jar, javadoc, and all the bells and whistles. */
    boolean compileOnly() {
      return "test".equals(name);
    }

    /** Launch JUnit Platform for this realm. */
    boolean containsTests() {
      return "test".equals(name);
    }

    @Override
    public String toString() {
      return "Realm{" + "name=" + name + ", source=" + source + '}';
    }
  }

  /** Build modules. */
  @FunctionalInterface
  interface ModuleBuilder {
    /** Build given modules and return list of modules actually built. */
    List<String> build(List<String> modules);
  }

  /** Build modules using default jigsaw directory layout. */
  class DefaultBuilder implements ModuleBuilder {
    final Run run;
    final Realm realm;
    final Path moduleSourcePath;

    DefaultBuilder(Run run, Realm realm) {
      this.run = run;
      this.realm = realm;
      this.moduleSourcePath = home.resolve(realm.source);
    }

    @Override
    public List<String> build(List<String> modules) {
      run.log(DEBUG, "Building %d module(s): %s", modules.size(), modules);
      compile(modules);
      if (realm.compileOnly()) {
        return modules;
      }
      try {
        for (var module : modules) {
          jarModule(module);
          jarSources(module);
          // TODO Create javadoc and "-javadoc.jar" for this module
        }
      } catch (Exception e) {
        throw new Error("Building modules failed!", e);
      }
      return List.copyOf(modules);
    }

    private void compile(List<String> modules) {
      var javac =
          new Args()
              .with(false, "-verbose")
              .with("-encoding", "UTF-8")
              .with("-Xlint")
              .with("-d", realm.compiledModules)
              .with("--module-version", version)
              .with("--module-source-path", moduleSourcePath)
              .with("--module", String.join(",", modules));

      var modulePath = new ArrayList<Path>();
      if (Files.exists(realm.packagedModules)) {
        modulePath.add(realm.packagedModules);
      }
      modulePath.addAll(realm.modulePaths.get("compile"));
      if (!modulePath.isEmpty()) {
        javac.with("--module-path", modulePath);
      }

      run.tool("javac", javac.toStringArray());
    }

    private void jarModule(String module) throws Exception {
      Files.createDirectories(realm.packagedModules);
      var modularJar = realm.packagedModules.resolve(module + '-' + version + ".jar");
      var jar =
          new Args()
              .with(debug, "--verbose")
              .with("--create")
              .with("--file", modularJar)
              .with("-C", realm.compiledModules.resolve(module))
              .with(".");
      run.tool("jar", jar.toStringArray());
    }

    private void jarSources(String module) throws Exception {
      Files.createDirectories(realm.packagedSources);
      var sourcesJar = realm.packagedSources.resolve(module + '-' + version + "-sources.jar");
      var jar =
          new Args()
              .with(debug, "--verbose")
              .with("--create")
              .with("--file", sourcesJar)
              .with("-C", moduleSourcePath.resolve(module))
              .with(".");
      run.tool("jar", jar.toStringArray());
    }
  }

  /** Build multi-release modules. */
  class MultiReleaseBuilder extends DefaultBuilder {

    private final Pattern javaReleasePattern = Pattern.compile("java-\\d+");

    MultiReleaseBuilder(Run run, Realm realm) {
      super(run, realm);
    }

    @Override
    public List<String> build(List<String> modules) {
      var result = new ArrayList<String>();
      for (var module : modules) {
        if (build(module)) {
          result.add(module);
        }
      }
      return result;
    }

    private boolean build(String module) {
      var names = Util.listDirectoryNames(moduleSourcePath.resolve(module));
      if (names.isEmpty()) {
        return false; // empty source path or just a sole "module-info.java" file...
      }
      if (!names.stream().allMatch(javaReleasePattern.asMatchPredicate())) {
        return false;
      }
      run.log(DEBUG, "Building multi-release module: %s", module);
      int base = 8; // TODO Find declared low base number: "java-*"
      for (var release = base; release <= Runtime.version().feature(); release++) {
        compile(module, base, release);
      }
      if (realm.compileOnly()) {
        return true;
      }
      try {
        jarModule(module, base);
        jarSources(module, base);
        // TODO Create "-javadoc.jar" for this multi-release module
      } catch (Exception e) {
        throw new Error("Building module " + module + " failed!", e);
      }
      return true;
    }

    private void compile(String module, int base, int release) {
      var moduleSourcePath = home.resolve(realm.source);
      var javaR = "java-" + release;
      var source = moduleSourcePath.resolve(module).resolve(javaR);
      if (Files.notExists(source)) {
        run.log(DEBUG, "Skipping %s, no source path exists: %s", javaR, source);
        return;
      }
      var destination = realm.compiledMulti.resolve(javaR);
      var javac =
          new Args()
              .with(false, "-verbose")
              .with("-encoding", "UTF-8")
              .with("-Xlint")
              .with("--release", release);
      if (release < 9) {
        javac.with("-d", destination.resolve(module));
        // TODO "-cp" ...
        javac.withEach(Util.listJavaFiles(source)); // javac.with("**/*.java");
      } else {
        javac.with("-d", destination);
        javac.with("--module-version", version);
        javac.with("--module-path", realm.modulePaths.get("compile"));
        var pathR = moduleSourcePath + File.separator + "*" + File.separator + javaR;
        var sources = List.of(pathR, "" + moduleSourcePath);
        javac.with("--module-source-path", String.join(File.pathSeparator, sources));
        javac.with(
            "--patch-module",
            module + '=' + realm.compiledMulti.resolve("java-" + base).resolve(module));
        javac.with("--module", module);
      }
      run.tool("javac", javac.toStringArray());
    }

    private void jarModule(String module, int base) throws Exception {
      Files.createDirectories(realm.packagedModules);
      var file = realm.packagedModules.resolve(module + '-' + version + ".jar");
      var source = realm.compiledMulti;
      var javaBase = source.resolve("java-" + base).resolve(module);
      var jar =
          new Args()
              .with(debug, "--verbose")
              .with("--create")
              .with("--file", file)
              // "base" classes
              .with("-C", javaBase)
              .with(".");
      // "base" + 1 .. N files
      for (var release = base + 1; release <= Runtime.version().feature(); release++) {
        var javaRelease = source.resolve("java-" + release).resolve(module);
        if (Files.notExists(javaRelease)) {
          continue;
        }
        jar.with("--release", release);
        jar.with("-C", javaRelease);
        jar.with(".");
      }
      run.tool("jar", jar.toStringArray());
    }

    private void jarSources(String module, int base) throws Exception {
      Files.createDirectories(realm.packagedSources);
      var file = realm.packagedSources.resolve(module + '-' + version + "-sources.jar");
      var source = home.resolve(realm.source).resolve(module);
      var javaBase = source.resolve("java-" + base);
      var jar =
          new Args()
              .with(debug, "--verbose")
              .with("--create")
              .with("--file", file)
              // "base" classes
              .with("-C", javaBase)
              .with(".");
      // "base" + 1 .. N files
      for (var release = base + 1; release <= Runtime.version().feature(); release++) {
        var javaRelease = source.resolve("java-" + release);
        if (Files.notExists(javaRelease)) {
          continue;
        }
        jar.with("--release", release);
        jar.with("-C", javaRelease);
        jar.with(".");
      }
      run.tool("jar", jar.toStringArray());
    }
  }

  /** Static helpers. */
  static final class Util {
    /** No instance permitted. */
    private Util() {
      throw new Error();
    }

    /** Download file from supplied uri to specified destination directory. */
    static Path download(boolean offline, Path folder, URI uri) throws Exception {
      // logger.accept("download(" + uri + ")");
      var fileName = extractFileName(uri);
      var target = Files.createDirectories(folder).resolve(fileName);
      var url = uri.toURL(); // fails for non-absolute uri
      if (offline) {
        if (Files.exists(target)) {
          // logger.accept("Offline mode is active and target already exists.");
          return target;
        }
        throw new IllegalStateException("Target is missing and being offline: " + target);
      }
      var connection = url.openConnection();
      try (var sourceStream = connection.getInputStream()) {
        var millis = connection.getLastModified();
        var lastModified = FileTime.fromMillis(millis == 0 ? System.currentTimeMillis() : millis);
        if (Files.exists(target)) {
          // logger.accept("Local target file exists. Comparing last modified timestamps...");
          var fileModified = Files.getLastModifiedTime(target);
          // logger.accept(" o Remote Last Modified -> " + lastModified);
          // logger.accept(" o Target Last Modified -> " + fileModified);
          if (fileModified.equals(lastModified)) {
            // logger.accept(String.format("Already downloaded %s previously.", fileName));
            return target;
          }
          // logger.accept("Local target file differs from remote source -- replacing it...");
        }
        // logger.accept("Transferring " + uri);
        try (var targetStream = Files.newOutputStream(target)) {
          sourceStream.transferTo(targetStream);
        }
        var contentDisposition = connection.getHeaderField("Content-Disposition");
        if (contentDisposition != null && contentDisposition.indexOf('=') > 0) {
          var newTarget = target.resolveSibling(contentDisposition.split("=")[1]);
          Files.move(target, newTarget);
          target = newTarget;
        }
        Files.setLastModifiedTime(target, lastModified);
        // logger.accept(String.format(" o Remote   -> %s", uri));
        // logger.accept(String.format(" o Target   -> %s", target.toUri()));
        // logger.accept(String.format(" o Modified -> %s", lastModified));
        // logger.accept(String.format(" o Size     -> %d bytes", Files.size(target)));
        // logger.accept(String.format("Downloaded %s successfully.", fileName));
      }
      return target;
    }

    /** Extract last path element from the supplied uri. */
    static String extractFileName(URI uri) {
      var path = uri.getPath(); // strip query and fragment elements
      return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Find first subdirectory below the given home path. */
    static Optional<Path> findFirstDirectory(Path home, String... paths) {
      return Arrays.stream(paths).map(home::resolve).filter(Files::isDirectory).findFirst();
    }

    /** Test supplied path for pointing to a regular Java source compilation unit file. */
    static boolean isJavaFile(Path path) {
      if (Files.isRegularFile(path)) {
        var name = path.getFileName().toString();
        if (name.endsWith(".java")) {
          return name.indexOf('.') == name.length() - 5; // single dot in filename
        }
      }
      return false;
    }

    /** Test supplied path for pointing to a regular Java archive file. */
    static boolean isJarFile(Path path) {
      return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar");
    }

    /** Return list of child directories directly present in {@code root} path. */
    static List<Path> listDirectories(Path root) {
      if (Files.notExists(root)) {
        return List.of();
      }
      try (var paths = Files.find(root, 1, (path, attr) -> Files.isDirectory(path))) {
        return paths.filter(path -> !root.equals(path)).collect(Collectors.toList());
      } catch (Exception e) {
        throw new Error("findDirectories failed for root: " + root, e);
      }
    }

    /** Return sorted list of child directory names directly present in {@code root} path. */
    static List<String> listDirectoryNames(Path root) {
      return listDirectories(root).stream()
          .map(root::relativize)
          .map(Path::toString)
          .sorted()
          .collect(Collectors.toList());
    }

    /** List all regular files matching the given filter. */
    static List<Path> listFiles(Collection<Path> roots, Predicate<Path> filter) {
      var files = new ArrayList<Path>();
      for (var root : roots) {
        try (var stream = Files.walk(root)) {
          stream.filter(Files::isRegularFile).filter(filter).forEach(files::add);
        } catch (Exception e) {
          throw new Error("Finding files failed for: " + roots, e);
        }
      }
      return files;
    }

    /** List all files with specified name in given root directory. */
    static List<Path> listFiles(Path root, Predicate<String> name) {
      return listFiles(List.of(root), path -> name.test(path.getFileName().toString()));
    }

    /** List all regular Java files in given root directory. */
    static List<Path> listJavaFiles(Path root) {
      return listFiles(List.of(root), Util::isJavaFile);
    }
  }
}
