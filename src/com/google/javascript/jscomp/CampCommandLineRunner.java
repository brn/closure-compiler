package com.google.javascript.jscomp;

import com.google.javascript.jscomp.CommandLineRunner;
import com.google.javascript.jscomp.CompilerOptions;

public class CampCommandLineRunner extends CommandLineRunner {

  public CampCommandLineRunner(String[] args) {
    // The superclass is responsible for parsing the command-line arguments.
    super(args);
  }


  @Override
  protected CompilerOptions createOptions() {
    // Let the superclass create the CompilerOptions using the values parsed
    // from the command-line arguments.
    CompilerOptions options = super.createOptions();
    return options;
  }


  @Override
  protected Compiler createCompiler() {
    return new CampCompiler(getErrorPrintStream());
  }
  
  
  /** Runs the Compiler */
  public static void main(String[] args) {
    CampCommandLineRunner runner = new CampCommandLineRunner(args);
    if (runner.shouldRunCompiler()) {
      runner.run();
    } else {
      System.exit(-1);
    }
  }
}
