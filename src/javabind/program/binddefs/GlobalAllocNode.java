package javabind.program.binddefs;

import soot.Type;

/*
 * @author Saswat Anand
 */
public abstract class GlobalAllocNode extends AllocNode
{
	public GlobalAllocNode(Type t)
	{
		super(t);
	}
}
