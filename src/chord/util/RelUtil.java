package chord.util;

import shord.project.analyses.ProgramRel;
import shord.project.ClassicProject;

/** Use by saying {@code import static chord.util.RelUtil.*;}. */
public final class RelUtil {
    private RelUtil() { /* no instance */ }
	public static ProgramRel pRel(String name) { return (ProgramRel) ClassicProject.g().getTrgt(name); }
}
