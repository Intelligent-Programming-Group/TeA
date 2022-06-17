/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 4.0.2
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package com.neuromancer42.tea.souffle.swig;

public class SwigInterfaceJNI {
  public final static native long new_StringVector__SWIG_0();
  public final static native long new_StringVector__SWIG_1(long jarg1, StringVector jarg1_);
  public final static native long StringVector_capacity(long jarg1, StringVector jarg1_);
  public final static native void StringVector_reserve(long jarg1, StringVector jarg1_, long jarg2);
  public final static native boolean StringVector_isEmpty(long jarg1, StringVector jarg1_);
  public final static native void StringVector_clear(long jarg1, StringVector jarg1_);
  public final static native long new_StringVector__SWIG_2(int jarg1, String jarg2);
  public final static native int StringVector_doSize(long jarg1, StringVector jarg1_);
  public final static native void StringVector_doAdd__SWIG_0(long jarg1, StringVector jarg1_, String jarg2);
  public final static native void StringVector_doAdd__SWIG_1(long jarg1, StringVector jarg1_, int jarg2, String jarg3);
  public final static native String StringVector_doRemove(long jarg1, StringVector jarg1_, int jarg2);
  public final static native String StringVector_doGet(long jarg1, StringVector jarg1_, int jarg2);
  public final static native String StringVector_doSet(long jarg1, StringVector jarg1_, int jarg2, String jarg3);
  public final static native void StringVector_doRemoveRange(long jarg1, StringVector jarg1_, int jarg2, int jarg3);
  public final static native void delete_StringVector(long jarg1);
  public final static native long new_SWIGSouffleProgram(long jarg1);
  public final static native void delete_SWIGSouffleProgram(long jarg1);
  public final static native void SWIGSouffleProgram_run(long jarg1, SWIGSouffleProgram jarg1_);
  public final static native void SWIGSouffleProgram_runAll(long jarg1, SWIGSouffleProgram jarg1_, String jarg2, String jarg3);
  public final static native void SWIGSouffleProgram_loadAll(long jarg1, SWIGSouffleProgram jarg1_, String jarg2);
  public final static native void SWIGSouffleProgram_printAll(long jarg1, SWIGSouffleProgram jarg1_, String jarg2);
  public final static native void SWIGSouffleProgram_dumpInputs(long jarg1, SWIGSouffleProgram jarg1_);
  public final static native void SWIGSouffleProgram_dumpOutputs(long jarg1, SWIGSouffleProgram jarg1_);
  public final static native long SWIGSouffleProgram_getRelNames(long jarg1, SWIGSouffleProgram jarg1_);
  public final static native long SWIGSouffleProgram_getRelSigns(long jarg1, SWIGSouffleProgram jarg1_);
  public final static native long SWIGSouffleProgram_getInputRelNames(long jarg1, SWIGSouffleProgram jarg1_);
  public final static native long SWIGSouffleProgram_getOutputRelNames(long jarg1, SWIGSouffleProgram jarg1_);
  public final static native void SWIGSouffleProgram_printProvenance(long jarg1, SWIGSouffleProgram jarg1_);
  public final static native long newInstance(String jarg1);
}
