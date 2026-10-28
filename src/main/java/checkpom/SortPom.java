package checkpom;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.util.List;

/** Reorders the direct child elements of {@code <project>} in a pom. */
@Mojo(name = "sort", aggregator = true, threadSafe = true)
public final class SortPom extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    public MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    public MavenSession session;

    /** The order in which the direct child tags of {@code <project>} should appear. */
    @Parameter
    public List<String> order;

    /** Tags after which a blank line is inserted. */
    @Parameter
    public List<String> newlines;

    /** Turns the reordering on or off. */
    @Parameter(defaultValue = "true")
    public boolean enabled;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!enabled) return;
        PomReorderer.reorder(project, session, order, newlines, getLog());
    }
}