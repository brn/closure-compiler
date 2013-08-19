package com.google.javascript.jscomp;

import java.io.PrintStream;

public class CampCompiler extends Compiler {

  public CampCompiler(PrintStream printStream) {
    super(printStream);
    CampUtil.setCompiler(this);
  }
  
  @Override
  PassConfig createPassConfigInternal() {
    return new CampPassConfig(options);
  }
}
