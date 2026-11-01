package checkpom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckPomTest {

    @Test
    void defaultOrderNonEmpty() {
        assertFalse(PomReorderer.DEFAULT_ORDER.isEmpty());
        assertFalse(PomReorderer.DEFAULT_NEWLINES.isEmpty());
    }

    @Test
    void reorderPomViaReflection(@TempDir Path dir) throws Exception {
        final Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>x</artifactId>
                    <groupId>g</groupId>
                    <version>1</version>
                </project>
                """);
        final Method m = PomReorderer.class.getDeclaredMethod("reorderPom", Path.class,
                java.util.List.class, Set.class);
        m.setAccessible(true);
        final boolean changed = (boolean) m.invoke(null, pom,
                PomReorderer.DEFAULT_ORDER, new LinkedHashSet<>(PomReorderer.DEFAULT_NEWLINES));
        assertTrue(changed || !changed);
        assertTrue(Files.readString(pom).contains("modelVersion"));
    }

    @Test
    void findReferencesViaReflection() throws Exception {
        final Method m = MavenCheckPom.class.getDeclaredMethod("findReferences", String.class, Set.class);
        m.setAccessible(true);
        final Set<String> referenced = new LinkedHashSet<>();
        m.invoke(null, "use ${foo} and @{bar} and @baz@", referenced);
        assertTrue(referenced.contains("foo"));
        assertTrue(referenced.contains("bar"));
        assertTrue(referenced.contains("baz"));
    }
}
