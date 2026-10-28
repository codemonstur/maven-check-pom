package checkpom;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Reorders the direct child elements of the {@code <project>} element of a pom. */
public final class PomReorderer {

    public static final List<String> DEFAULT_ORDER = List.of(
            "modelVersion", "parent", "groupId", "artifactId", "version", "packaging",
            "name", "description", "url", "inceptionYear", "organization", "licenses",
            "scm", "developers", "contributors", "mailingLists", "prerequisites",
            "modules", "issueManagement", "ciManagement", "distributionManagement",
            "properties", "dependencyManagement", "dependencies", "repositories",
            "pluginRepositories", "build", "reports", "reporting", "profiles");

    public static final List<String> DEFAULT_NEWLINES = List.of(
            "modelVersion", "parent", "version", "packaging", "url", "licenses", "developers",
            "contributors", "mailingLists", "prerequisites", "modules", "scm",
            "issueManagement", "ciManagement", "distributionManagement", "properties",
            "dependencyManagement", "dependencies", "repositories", "pluginRepositories",
            "build", "reports", "reporting", "profiles");

    private PomReorderer() {
    }

    public static void reorder(final MavenProject project, final MavenSession session,
                               List<String> order, List<String> newlines, final Log log)
            throws MojoFailureException {
        if (order == null || order.isEmpty()) order = DEFAULT_ORDER;
        if (newlines == null) newlines = DEFAULT_NEWLINES;

        final var newlineSet = new LinkedHashSet<>(newlines);
        final var poms = new LinkedHashSet<Path>();
        collectPoms(project, session, poms);

        for (final var pom : poms) {
            try {
                if (reorderPom(pom, order, newlineSet))
                    log.info("Reordered the direct children of " + pom);
            } catch (final IOException e) {
                log.warn("Could not reorder '" + pom + "': " + e.getMessage());
            }
        }
    }

    private static void collectPoms(final MavenProject project, final MavenSession session,
                                   final Set<Path> poms) {
        final var toVisit = new java.util.ArrayDeque<Path>();
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

            String content;
            try {
                content = Files.readString(pom, UTF_8);
            } catch (final IOException e) {
                continue;
            }

            try {
                final var document = parse(content);
                for (final var module : findModules(document)) {
                    final var resolved = resolveModule(pom, module);
                    if (resolved != null) toVisit.add(resolved);
                }
            } catch (final Exception e) {
                // ignore unreadable poms
            }
        }
    }

    private static org.w3c.dom.Document parse(final String content)
            throws javax.xml.parsers.ParserConfigurationException, org.xml.sax.SAXException, IOException {
        final var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(content.getBytes(UTF_8)));
    }

    private static List<String> findModules(final org.w3c.dom.Document document) {
        final var modules = new ArrayList<String>();
        final var root = document.getDocumentElement();
        addModules(child(root, "modules"), modules);

        final var profiles = children(root, "profiles");
        for (final var profileContainer : profiles)
            for (final var profile : children(profileContainer, "profile"))
                addModules(child(profile, "modules"), modules);
        return modules;
    }

    private static void addModules(final org.w3c.dom.Element modules, final List<String> collected) {
        if (modules == null) return;
        for (final var element : children(modules, "module")) {
            final var name = element.getTextContent().trim();
            if (!name.isEmpty()) collected.add(name);
        }
    }

    private static Path resolveModule(final Path pom, final String module) {
        var candidate = pom.getParent().resolve(module);
        if (Files.isDirectory(candidate)) candidate = candidate.resolve("pom.xml");
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static org.w3c.dom.Element child(final org.w3c.dom.Element parent, final String name) {
        final var found = children(parent, name);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<org.w3c.dom.Element> children(final org.w3c.dom.Element parent, final String name) {
        final var found = new ArrayList<org.w3c.dom.Element>();
        final var nodes = parent.getChildNodes();
        for (var i = 0; i < nodes.getLength(); i++) {
            final var node = nodes.item(i);
            if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && name.equals(localName(node)))
                found.add((org.w3c.dom.Element) node);
        }
        return found;
    }

    private static String localName(final org.w3c.dom.Node node) {
        final var local = node.getLocalName();
        return local != null ? local : node.getNodeName();
    }

    private static boolean reorderPom(final Path pom, final List<String> order,
                                     final Set<String> newlinesAfter) throws IOException {
        final var content = Files.readString(pom, UTF_8);
        final var rootStart = content.indexOf("<project");
        if (rootStart < 0) return false;
        final var rootTagEnd = content.indexOf('>', rootStart);
        if (rootTagEnd < 0) return false;
        final var rootEnd = content.lastIndexOf("</project>");
        if (rootEnd < rootStart) return false;

        final var prefix = content.substring(0, rootTagEnd + 1);
        final var rootContent = content.substring(rootTagEnd + 1, rootEnd);
        final var suffix = content.substring(rootEnd);

        final var children = parseChildren(rootContent);
        if (children.size() <= 1) return false;

        final var rank = new HashMap<String, Integer>();
        for (var i = 0; i < order.size(); i++) rank.put(order.get(i), i);

        final var sorted = new ArrayList<>(children);
        sorted.sort(Comparator
                .comparingInt((Child child) -> rank.getOrDefault(child.name, Integer.MAX_VALUE))
                .thenComparingInt(child -> child.index));

        final var body = new StringBuilder();
        var previous = (String) null;
        for (var i = 0; i < sorted.size(); i++) {
            final var child = sorted.get(i);
            if (i > 0) {
                body.append("\n");
                if (newlinesAfter.contains(previous)) body.append("\n");
            }
            body.append("    ").append(child.raw);
            previous = child.name;
        }

        final var result = prefix + "\n" + body + "\n\n" + suffix;
        if (result.equals(content)) return false;

        Files.writeString(pom, result, UTF_8);
        return true;
    }

    private static List<Child> parseChildren(final String content) {
        final var children = new ArrayList<Child>();
        var i = 0;
        final var n = content.length();
        var index = 0;
        while (i < n) {
            if (Character.isWhitespace(content.charAt(i))) {
                i++;
                continue;
            }
            if (content.startsWith("<!--", i)) {
                final var end = content.indexOf("-->", i);
                i = end < 0 ? n : end + 3;
                continue;
            }
            if (content.charAt(i) != '<') {
                i++;
                continue;
            }
            if (i + 1 < n && content.charAt(i + 1) == '/') {
                final var end = content.indexOf('>', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (i + 1 < n && (content.charAt(i + 1) == '?' || content.charAt(i + 1) == '!')) {
                final var end = content.indexOf('>', i);
                i = end < 0 ? n : end + 1;
                continue;
            }

            final var end = findMatching(content, i);
            if (end < 0) break;

            final var tagEnd = content.indexOf('>', i);
            var tagSpec = content.substring(i + 1, tagEnd).trim();
            if (tagSpec.endsWith("/")) tagSpec = tagSpec.substring(0, tagSpec.length() - 1).trim();
            final var name = tagSpec.split("\\s+")[0];

            children.add(new Child(name, content.substring(i, end), index++));
            i = end;
        }
        return children;
    }

    private static int findMatching(final String content, final int start) {
        final var firstTagEnd = content.indexOf('>', start);
        if (firstTagEnd < 0) return -1;

        var tagSpec = content.substring(start + 1, firstTagEnd).trim();
        final var selfClosing = tagSpec.endsWith("/");
        if (selfClosing) tagSpec = tagSpec.substring(0, tagSpec.length() - 1).trim();
        final var name = tagSpec.split("\\s+")[0];

        if (selfClosing) return firstTagEnd + 1;

        var depth = 1;
        var i = firstTagEnd + 1;
        final var n = content.length();
        while (i < n) {
            final var next = content.indexOf('<', i);
            if (next < 0) return n;

            if (next > i) {
                i = next;
                continue;
            }

            if (content.startsWith("<!--", i)) {
                final var end = content.indexOf("-->", i);
                i = end < 0 ? n : end + 3;
                continue;
            }
            if (content.startsWith("<![CDATA[", i)) {
                final var end = content.indexOf("]]>", i);
                i = end < 0 ? n : end + 3;
                continue;
            }

            final var tagEnd = content.indexOf('>', i);
            if (tagEnd < 0) return n;

            final var closing = i + 1 < n && content.charAt(i + 1) == '/';
            var spec = content.substring(i + 1, tagEnd).trim();
            if (closing) spec = spec.substring(1).trim();
            final var self = spec.endsWith("/");
            if (self) spec = spec.substring(0, spec.length() - 1).trim();
            final var tagName = spec.split("\\s+")[0];

            if (closing) {
                depth--;
                if (depth == 0) return tagEnd + 1;
            } else if (!self) {
                depth++;
            }

            i = tagEnd + 1;
        }
        return n;
    }

    private static final class Child {
        final String name;
        final String raw;
        final int index;

        Child(final String name, final String raw, final int index) {
            this.name = name;
            this.raw = raw;
            this.index = index;
        }
    }
}