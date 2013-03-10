package jp.co.cyberagent.camp;

import jp.co.cyberagnet.camp.processors.module.CampModuleProcessor;
import jp.co.cyberagnet.camp.processors.injector.InjectionProcessor;
import com.google.common.collect.HashMultimap;
import com.google.javascript.jscomp.CommandLineRunner;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.CustomPassExecutionTime;

public class CampCommandLineRunner extends CommandLineRunner {

  protected CampCommandLineRunner(String[] args) {
    // The superclass is responsible for parsing the command-line arguments.
    super(args);
  }

  @Override
  protected CompilerOptions createOptions() {
    // Let the superclass create the CompilerOptions using the values parsed
    // from the command-line arguments.
    CompilerOptions options = super.createOptions();

    // ENABLE ADDITIONAL OPTIONS HERE.
    if (options.customPasses == null) {
      options.customPasses = HashMultimap.create();
    }
    options.customPasses.put(CustomPassExecutionTime.BEFORE_CHECKS, new CampModuleProcessor(getCompiler()));
	options.customPasses.put(CustomPassExecutionTime.BEFORE_OPTIMIZATIONS, new InjectionProcessor(getCompiler()));

    return options;
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
