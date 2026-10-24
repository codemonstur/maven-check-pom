package checkpom;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static org.apache.maven.plugins.annotations.LifecyclePhase.VALIDATE;

/**
 * Checks that every property defined in the poms of the build is still referenced somewhere.
 * A property counts as used when it appears as '${name}', '@{name}' or '@name@' in a pom of the
 * reactor, in a file next to a pom, or in a file below a 'src' directory. Properties that maven
 * plugins read directly from the project (like 'maven.compiler.source') are never referenced in
 * the poms and are ignored by definition.
 */
@Mojo(defaultPhase = VALIDATE, name = "check", threadSafe = true, aggregator = true)
public final class MavenCheckPom extends AbstractMojo {

    /**
     * Properties that plugins read from the project without them ever being referenced in a pom,
     * so they can never be proven unused by scanning text.
     */
    private static final Set<String> BUILT_IN_IGNORED = Set.of(
            "maven.compiler.source", "maven.compiler.target", "maven.compiler.release",
            "maven.compiler.encoding", "maven.compiler.testSource", "maven.compiler.testTarget",
            "maven.compiler.testRelease", "maven.compiler.parameters",
            "maven.main.skip", "maven.test.skip", "argLine",
            "project.build.sourceEncoding", "project.reporting.outputEncoding");

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]+)\\}|@\\{([^}]+)\\}|@([A-Za-z0-9._:-]+)@");

    /** The project the plugin runs in, the root of the reactor for this aggregator. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    public MavenProject project;
    /** The session, used to find all projects of the reactor. */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    public MavenSession session;

    /** Turns the whole check on or off. */
    @Parameter(defaultValue = "true")
    public boolean enabled;
    /** Prints a warning in the log for each unused property. */
    @Parameter(defaultValue = "true")
    public boolean printUnused;
    /** Fails the build when any unused property is found. */
    @Parameter(defaultValue = "true")
    public boolean failBuildOnUnused;

    /** Property names that are allowed to stay unused, for example when they are read outside the build. */
    @Parameter
    public Set<String> ignored;
    /** Optional file with one property name per line, '#' starts a comment. */
    @Parameter
    public File ignoredFile;

    /**
     * Scans the poms of the reactor and fails the build when a defined property is never referenced.
     */
    public void execute() throws MojoFailureException {
        if (!enabled) return;

        final var log = getLog();
        final var ignoredNames = parseIgnored();

        final var poms = new LinkedHashSet<Path>();
        final var defined = new LinkedHashMap<String, Set<Path>>();
        collectPoms(poms, defined);

        final var scanned = new LinkedHashSet<Path>(poms);
        for (final var pom : poms) collectScanFiles(pom.getParent(), scanned);

        final var referenced = new HashSet<String>();
        for (final var file : scanned) {
            final var content = readFile(file);
            if (content != null) findReferences(content, referenced);
        }

        final var unused = new LinkedHashSet<String>();
        var ignoredCount = 0;
        for (final var name : defined.keySet()) {
            if (referenced.contains(name)) continue;
            if (ignoredNames.contains(name)) {
                ignoredCount++;
                continue;
            }
            unused.add(name);
        }

        log.info("Checked " + defined.size() + " properties defined in " + poms.size()
                + " pom(s), found " + unused.size() + " unused and " + ignoredCount + " ignored.");
        if (printUnused) {
            for (final var name : unused)
                log.warn("The property '" + name + "' defined in " + describe(defined.get(name))
                        + " is not used");
        }
        if (failBuildOnUnused && !unused.isEmpty())
            throw new MojoFailureException("Unused properties found");
    }

    /**
     * Visits every pom of the reactor and every pom reachable through a &lt;module&gt; of a visited
     * pom, and collects the names of the properties each of them defines.
     */
    private void collectPoms(final Set<Path> poms, final Map<String, Set<Path>> defined) {
        final var toVisit = new ArrayDeque<Path>();
        final var projects = session.getProjects();
        if (projects != null) {
            for (final var reactorProject : projects) {
                final var file = reactorProject.getFile();
                if (file != null) toVisit.add(file.toPath());
            }
        }
        if (toVisit.isEmpty() && project.getFile() != null) toVisit.add(project.getFile().toPath());

        while (!toVisit.isEmpty()) {
            final var pom = toVisit.poll().toAbsolutePath().normalize();
            if (!poms.add(pom)) continue;

            final var content = readFile(pom);
            if (content == null) continue;

            try {
                final var document = parse(content);
                collectProperties(document, pom, defined);
                for (final var module : findModules(document)) {
                    final var resolved = resolveModule(pom, module);
                    if (resolved != null) toVisit.add(resolved);
                }
            } catch (final ParserConfigurationException | SAXException | IOException e) {
                getLog().warn("Could not read the pom '" + display(pom) + "': " + e.getMessage());
            }
        }
    }

    private static Document parse(final String content)
            throws ParserConfigurationException, SAXException, IOException {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(content.getBytes(UTF_8)));
    }

    /**
     * Collects the element names of the top level &lt;properties&gt; block and of the
     * &lt;properties&gt; block of every profile, keyed by the pom that defines them.
     */
    private static void collectProperties(final Document document, final Path pom,
                                          final Map<String, Set<Path>> defined) {
        final var root = document.getDocumentElement();
        addPropertyNames(child(root, "properties"), pom, defined);

        for (final var profiles : children(root, "profiles"))
            for (final var profile : children(profiles, "profile"))
                addPropertyNames(child(profile, "properties"), pom, defined);
    }

    private static void addPropertyNames(final Element properties, final Path pom,
                                         final Map<String, Set<Path>> defined) {
        if (properties == null) return;

        final var nodes = properties.getChildNodes();
        for (var i = 0; i < nodes.getLength(); i++) {
            final var node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;

            final var name = localName(node);
            if (BUILT_IN_IGNORED.contains(name)) continue;
            defined.computeIfAbsent(name, key -> new LinkedHashSet<>()).add(pom);
        }
    }

    /** The names of all &lt;module&gt; entries, top level and inside profiles. */
    private static List<String> findModules(final Document document) {
        final var modules = new ArrayList<String>();
        final var root = document.getDocumentElement();
        addModules(child(root, "modules"), modules);

        for (final var profiles : children(root, "profiles"))
            for (final var profile : children(profiles, "profile"))
                addModules(child(profile, "modules"), modules);
        return modules;
    }

    private static void addModules(final Element modules, final List<String> collected) {
        if (modules == null) return;

        for (final var element : children(modules, "module")) {
            final var name = element.getTextContent().trim();
            if (!name.isEmpty()) collected.add(name);
        }
    }

    /** A module entry points at a directory or at a pom file, both relative to the pom that lists it. */
    private static Path resolveModule(final Path pom, final String module) {
        var candidate = pom.getParent().resolve(module);
        if (Files.isDirectory(candidate)) candidate = candidate.resolve("pom.xml");
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    /** Adds the files directly next to a pom and everything below its src directory. */
    private void collectScanFiles(final Path basedir, final Set<Path> scanned) {
        if (basedir == null || !Files.isDirectory(basedir)) return;

        try (var entries = Files.list(basedir)) {
            entries.filter(Files::isRegularFile).map(Path::toAbsolutePath).forEach(scanned::add);
        } catch (final IOException e) {
            getLog().warn("Could not list '" + display(basedir) + "': " + e.getMessage());
        }

        final var src = basedir.resolve("src");
        if (!Files.isDirectory(src)) return;

        try (var entries = Files.walk(src)) {
            entries.filter(Files::isRegularFile).map(Path::toAbsolutePath).forEach(scanned::add);
        } catch (final IOException e) {
            getLog().warn("Could not list '" + display(src) + "': " + e.getMessage());
        }
    }

    /** Collects every name referenced as '${name}', '@{name}' or '@name@' in the text. */
    private static void findReferences(final String text, final Set<String> referenced) {
        final var matcher = REFERENCE.matcher(text);
        while (matcher.find()) {
            var name = matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            if (name == null) continue;

            name = name.trim();
            if (!name.isEmpty()) referenced.add(name);
        }
    }

    private Set<String> parseIgnored() throws MojoFailureException {
        final var names = new LinkedHashSet<String>();
        if (ignored != null) names.addAll(ignored);
        if (ignoredFile != null) names.addAll(readIgnoredFile(ignoredFile));

        final var property = System.getProperty("checkpom.ignored");
        if (property != null && !property.isBlank())
            for (final var name : property.split(","))
                if (!name.isBlank()) names.add(name.trim());
        return names;
    }

    private static Set<String> readIgnoredFile(final File file) throws MojoFailureException {
        if (!file.isFile())
            throw new MojoFailureException("The ignored file '" + file + "' does not exist");

        try {
            final var names = new LinkedHashSet<String>();
            for (final var line : Files.readAllLines(file.toPath())) {
                final var name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                names.add(name);
            }
            return names;
        } catch (final IOException e) {
            throw new MojoFailureException("Failed to read the ignored file '" + file + "'", e);
        }
    }

    private String readFile(final Path file) {
        try {
            return new String(Files.readAllBytes(file), UTF_8);
        } catch (final IOException e) {
            getLog().warn("Could not read '" + display(file) + "': " + e.getMessage());
            return null;
        }
    }

    /** The path of a file relative to the directory maven was started in, for readable messages. */
    private String display(final Path path) {
        try {
            final var root = session.getExecutionRootDirectory();
            if (root == null) return path.toString();
            return Path.of(root).toAbsolutePath().normalize().relativize(path).toString();
        } catch (final IllegalArgumentException e) {
            return path.toString();
        }
    }

    private String describe(final Set<Path> poms) {
        return poms.stream().map(this::display).collect(joining(", "));
    }

    private static String localName(final Node node) {
        final var local = node.getLocalName();
        return local != null ? local : node.getNodeName();
    }

    private static Element child(final Element parent, final String name) {
        final var found = children(parent, name);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<Element> children(final Element parent, final String name) {
        final var found = new ArrayList<Element>();
        final var nodes = parent.getChildNodes();
        for (var i = 0; i < nodes.getLength(); i++) {
            final var node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(localName(node)))
                found.add((Element) node);
        }
        return found;
    }

}
