package org.apache.tools.ant.taskdefs.optional.watch;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

/** web-ui defines its dev-only <watch> task at the top level of its build.xml, so ant needs the
 *  class to exist even for a build that never watches. This stands in for lib/watch-task-1.0.jar. */
public class WatchTask extends Task {
    @Override
    public void execute() {
        throw new BuildException("watch is not available in this build");
    }
}
