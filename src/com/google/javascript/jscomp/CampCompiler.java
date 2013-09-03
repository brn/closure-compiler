package com.google.javascript.jscomp;

import java.io.PrintStream;

public class CampCompiler extends Compiler {

  private CodingConvention convention;

  private CampTypeInfo campTypeInfo;

  public CampCompiler(PrintStream printStream, CampTypeInfo campTypeInfo) {
    super(printStream);
    CampUtil.setCompiler(this);
    convention = new CampCodingConvention(campTypeInfo);
    this.campTypeInfo = campTypeInfo;
  }

  

  /**
   * @return the campTypeInfo
   */
  public CampTypeInfo getCampTypeInfo() {
    return campTypeInfo;
  }



  @Override
  PassConfig createPassConfigInternal() {
    return new CampPassConfig(options);
  }


  @Override
  public CodingConvention getCodingConvention() {
    return this.convention;
  }

}
